package cart.server;

import cart.*;
import cart.ClientCommunicationGrpc.ClientCommunicationImplBase;
import cart.adapter.ReadOnlyGrpc;
import cart.adapter.ReadOnlyRequestBatch;
import cart.adapter.ReadOnlyResultHashes;
import cart.binding.JNIBinding;
import cart.types.*;
import cart.types.aggregation.AggregatedSignature;
import cart.types.aggregation.AggregationRequest;
import cart.types.signature.BLSSignature;
import cart.types.signature.BatchSignatureRequest;
import cart.types.signature.SignatureRequest;
import cart.types.signature.SingleSignatureRequest;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class ServerSideCART {
    private final Set<Integer> blockList;
    private final HashMap<Integer, String> hostInfo;
    private final int DEFAULT_COLLECTOR_PORT = 51000;

    private final HashMap<Long, ServiceResponse.Builder> responseBuilder;
    private final HashMap<Long, StreamObserver<ServiceResponse>> clientObservers;

    private final HashMap<Long, ResponseStatus> processedRequests;
    private final HashSet<Long> resubmittedRequests;
    private final HashMap<Long, long[]> receivedBatchLookup;
    private final AtomicLong currentBatchNumber;

    private Server clientHandler;
    private Server collectorHandler;
    private RequestHandler requestHandler;
    private AggregationHandler aggregationHandler;
    private SignatureHandler signatureHandler;

    private final ReentrantLock responseLock;
    private final ReentrantLock aggregationLock;
    private final ReentrantLock batchLock;

    private final int numReplicas;
    private final int orderedThreshold;
    private final int unorderedThreshold;

    private final int replicaID;
    private ReplicaRole role;
    private final boolean tentativeAggregation;
    private final HashMap<Long, HashSet<BLSSignature>> signatureShares;
    private final HashSet<Long> aggregationInProgress;
    private final HashMap<Long, RequestType> signedRequests;
    private final boolean batchSignatures;

    // Required for benchmarking
    private final AtomicBoolean firstRequest;
    private final AtomicLong requestCounter;
    private final int benchmarkDuration;
    private final int measurementInterval;
    private final boolean runThroughputBenchmark;
    private final boolean runLatencyBenchmark;
    private ArrayList<Double> throughput;
    private double intervalStart;

    private final AtomicBoolean latencyBenchmark;
    private final ConcurrentHashMap<Long, Long> arrivalTime;
    private final ConcurrentHashMap<Long, Long> orderingTime;
    private final ConcurrentHashMap<Long, Long> readonlyTime;
    private final ConcurrentHashMap<Long, Long> signatureTime;
    private final ConcurrentHashMap<Long, Long> distributionTime;
    private final ConcurrentHashMap<Long, Long> aggregationTime;
    private final ConcurrentHashMap<Long, Long> replyTime;

    private final boolean debug_;

    private boolean byzantineAggregator;
    private final double byzantineAttackRate;
    private final int attackStartingPoint;
    private final int attackDuration;
    private final AtomicLong attackCounter;


    private class ClientCommunicationService extends ClientCommunicationImplBase {
        private SocketChannel socketChannel;

        @Override
        public void requestPublicKey(PublicKeyRequest request, StreamObserver<PublicKeyResponse> responseObserver) {
            System.out.println("Received public key request");
            JNIBinding.init_relic();
            PublicKeyResponse response = PublicKeyResponse.newBuilder()
                    .addPublicKeys(ByteString.copyFrom(JNIBinding.getGroupPublicKey(orderedThreshold)))
                    .addPublicKeys(ByteString.copyFrom(JNIBinding.getGroupPublicKey(unorderedThreshold)))
                    .addThresholds(orderedThreshold)
                    .addThresholds(unorderedThreshold)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<ServiceRequest> serviceStream(StreamObserver<ServiceResponse> responseObserver) {
            return new StreamObserver<ServiceRequest>() {
                private long clientID;

                @Override
                public void onNext(ServiceRequest request) {
                    // Sending out async request to the remaining replicas
                    if (role.equals(ReplicaRole.Leader)) {
                        //submitTOMRequest(request);
                        requestHandler.submitRequest(request);
                    } else {
                        // TODO Backup and Aggregator replicas should start the monitoring system here
                    }

                    if(role.equals(ReplicaRole.Aggregator) && latencyBenchmark.get())
                        arrivalTime.put(request.getId(), System.nanoTime());

                    long requestID = request.getId();
                    long clientID = (requestID & 0x7FFFFFFF00000000L) >> 32;
                    try {
                        responseLock.lock();
                        if (!clientObservers.containsKey(clientID)) {
                            this.clientID = clientID;
                            clientObservers.put(clientID, responseObserver);
                        }

                        if (!responseBuilder.containsKey(requestID)) {
                            responseBuilder.put(requestID, ServiceResponse.newBuilder()
                                    .setId(requestID)
                                    .setRequestType(request.getRequestType()));
                        } else if (role.equals(ReplicaRole.Aggregator)) {
                            verifyAndReply(requestID);
                        }
                    } finally {
                        responseLock.unlock();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    System.err.println("Client StreamObserver Error");
                }

                @Override
                public void onCompleted() {
                    try {
                        responseLock.lock();
                        clientObservers.put(clientID, null);
                    } finally {
                        responseLock.unlock();
                    }
                }
            };
        }
    }

    private class RequestHandler implements Runnable {
        private final LinkedBlockingQueue<ServiceRequest> openRequests;
        private final ByteBuffer socketBuffer;
        private SocketChannel socketChannel;
        private final HashMap<Integer, StreamObserver<ReadOnlyRequestBatch>> readOnlyStreamObserver;
        private boolean running;

        private final ReentrantLock readonlyLock;
        private final HashMap<Long, ByteString> pendingRequests;
        private final HashMap<Long, HashMap<Integer, ByteString>> pendingResponses;

        private class ReadOnlyStreamObserver implements StreamObserver<ReadOnlyResultHashes> {
            private final int replicaID;

            public ReadOnlyStreamObserver(int replicaID) {
                this.replicaID = replicaID;
            }

            @Override
            public void onNext(ReadOnlyResultHashes response) {
                try {
                    readonlyLock.lock();
                    for (int i = 0; i < response.getIdCount(); i++) {
                        long requestID = response.getId(i);
                        ByteString responseHash = response.getHashes(i);

                        // Filter out late arriving responses
                        if (!pendingRequests.containsKey(requestID))
                            continue;

                        if (!pendingResponses.containsKey(requestID))
                            pendingResponses.put(requestID, new HashMap<>());

                        if (!pendingResponses.get(requestID).containsKey(replicaID))
                            pendingResponses.get(requestID).put(replicaID, responseHash);

                        int numResponses = pendingResponses.get(requestID).keySet().size();
                        if (numResponses >= unorderedThreshold) {
                            int matchingReplies = 1;
                            boolean quorumReached = false;
                            for (int replicaID : pendingResponses.get(requestID).keySet()) {
                                if (this.replicaID != replicaID && pendingResponses.get(requestID).get(replicaID).equals(responseHash)) {
                                    if (++matchingReplies >= unorderedThreshold) {
                                        quorumReached = true;
                                        break;
                                    }
                                }
                            }

                            if (!quorumReached && pendingResponses.get(requestID).size() == numReplicas) {
                                System.out.println("Re-submitting unordered request with requestID " + requestID);
                                try {
                                    openRequests.put(ServiceRequest.newBuilder()
                                            .setRequest(pendingRequests.get(requestID))
                                            .setId(requestID)
                                            .setRequestType(RequestType.ORDERED_REQUEST)
                                            .build());

                                    pendingRequests.remove(requestID);
                                    pendingResponses.remove(requestID);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            } else if (quorumReached) {
                                pendingRequests.remove(requestID);
                                pendingResponses.remove(requestID);
                            }
                        }

                    }
                } finally {
                    readonlyLock.unlock();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("Readonly stream: An error has occurred");
                throwable.printStackTrace();
            }

            @Override
            public void onCompleted() {
                System.out.println("Readonly stream has been terminated");
            }
        }

        public RequestHandler() {
            this.openRequests = new LinkedBlockingQueue<>();
            this.socketBuffer = ByteBuffer.allocate(65536);
            this.running = true;
            this.readOnlyStreamObserver = new HashMap<>();

            this.readonlyLock = new ReentrantLock();
            this.pendingRequests = new HashMap<>();
            this.pendingResponses = new HashMap<>();

            hostInfo.forEach((hostID, hostAddress) -> {
                ManagedChannel readOnlyChannel = ManagedChannelBuilder.forTarget(hostAddress + ":" + (52015)).usePlaintext().build();
                ReadOnlyGrpc.ReadOnlyStub readOnlyStub = ReadOnlyGrpc.newStub(readOnlyChannel).withWaitForReady();
                readOnlyStreamObserver.put(hostID, readOnlyStub.submitReadOnlyRequests(new ReadOnlyStreamObserver(hostID)));
            });
        }

        public void submitRequest(ServiceRequest request) {
            try {
                openRequests.put(request);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        private void submitTOMRequest(ServiceRequest request) {
            /* Using the following header format
            31                                                              0
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |O|                   RequestLength (31 Bit)                    |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                    CART RequestID  (64 Bit)                   |
            |                                                               |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            */
            int requestLength = request.getRequest().size();
            boolean ordered = request.getRequestType().equals(RequestType.ORDERED_REQUEST);

            socketBuffer.limit(Long.BYTES + Integer.BYTES + requestLength);
            socketBuffer.putInt(ordered ? requestLength : (1 << 31) | requestLength);
            socketBuffer.putLong(request.getId());
            socketBuffer.put(request.getRequest().toByteArray());
            socketBuffer.flip();

            while (socketBuffer.hasRemaining()) {
                try {
                    socketChannel.write(socketBuffer);
                } catch (IOException e) {
                    System.err.println("An error occurred while trying to submit command");
                    e.printStackTrace();
                }
            }

            if (debug_)
                System.out.println("Server-Side CART: Submitted " + (ordered ? "ordered " : "unordered ")
                        + "TOM request with CART ID " + request.getId());
            socketBuffer.clear();
        }

        private void submitUnorderedRequest(ReadOnlyRequestBatch request) {
            try {
                readonlyLock.lock();
                for (int i = 0; i < request.getRequestsCount(); i++) {
                    pendingRequests.put(request.getId(i), request.getRequests(i));
                }
            } finally {
                readonlyLock.unlock();
            }

            for (var observer : readOnlyStreamObserver.values()) {
                observer.onNext(request);
            }
        }

        @Override
        public void run() {
            // Try to establish the connection with the UDS socket
            UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of("/tmp/cart-client.socket");
            try {
                socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
                socketChannel.configureBlocking(true);
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                while (true) {
                    try {
                        socketChannel.connect(socketAddress);
                        break;
                    } catch (IOException e) {
                        System.out.println("Waiting for BFT-Client socket to become available - retrying in 1s");
                        Thread.sleep(1000);
                    }
                }

                ReadOnlyRequestBatch.Builder readOnlyRequest = ReadOnlyRequestBatch.newBuilder();
                while (true) {
                    int numPendingRequests = openRequests.size();
                    if (role.equals(ReplicaRole.Leader)) {
                        for (int i = 0; i < numPendingRequests; i++) {
                            ServiceRequest request = openRequests.take();
                            if (request.getRequestType().equals(RequestType.ORDERED_REQUEST)) {
                                submitTOMRequest(request);
                            } else {
                                readOnlyRequest.addRequests(request.getRequest())
                                        .addId(request.getId());
                            }
                        }
                        if (readOnlyRequest.getRequestsCount() > 0) {
                            submitUnorderedRequest(readOnlyRequest.build());
                            readOnlyRequest.clear();
                        }
                    } else {
                        // TODO Backup and Aggregator replicas should start the monitoring system here
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void shutdown() {
            this.running = false;
            try {
                this.socketChannel.close();
            } catch (IOException e) {
                System.err.println("Error while closing the result SocketChannel");
            }
        }
    }

    private class DebugService extends ClientDebugGrpc.ClientDebugImplBase {

        @Override
        public void clientDebug(ClientDebugRequest request, StreamObserver<ClientDebugResponse> responseObserver) {
            long requestID = request.getRequestID();
            System.out.println("Received debug request for requestID: " + requestID);

            ClientDebugResponse.Builder debugResponse = ClientDebugResponse.newBuilder().setRequestID(requestID)
                    .setReplicaID(replicaID);

            try {
                responseLock.lock();
                debugResponse.setRequestType(responseBuilder.get(requestID).getRequestType());
                if (responseBuilder.containsKey(requestID)) {
                    debugResponse.setBuilderIsPresent(true);
                    if (!responseBuilder.get(requestID).getResponse().equals(ByteString.EMPTY)) {
                        debugResponse.setResponseIsPresent(true);
                    }
                    if (!responseBuilder.get(requestID).getSignature().equals(ByteString.EMPTY)) {
                        debugResponse.setSignatureIsPresent(true);
                    }
                    if (processedRequests.containsKey(requestID)) {
                        debugResponse.setProcessedRequests(true);
                        if (processedRequests.get(requestID).equals(ResponseStatus.TENTATIVE)
                                || processedRequests.get(requestID).equals(ResponseStatus.DEFINITIVE)) {
                            debugResponse.setResponded(true);
                        }
                    }
                }
            } finally {
                responseLock.unlock();
            }

            checkIfReadyForAggregation(requestID, debugResponse.getRequestType().equals(RequestType.ORDERED_REQUEST), true);

            try {
                aggregationLock.lock();
                if (signatureShares.containsKey(requestID))
                    debugResponse.setSignatureSharesPresent(signatureShares.get(requestID).size());
                else
                    debugResponse.setSignatureSharesPresent(0);

            } finally {
                aggregationLock.unlock();
            }

            responseObserver.onNext(debugResponse.build());
            responseObserver.onCompleted();
        }
    }

    private class SignatureCollectorService extends ResponseCollectorGrpc.ResponseCollectorImplBase {
        @Override
        public StreamObserver<SignedResponse> submitResponseStream(StreamObserver<SignatureAck> responseObserver) {
            return new StreamObserver<SignedResponse>() {
                @Override
                public void onNext(SignedResponse signedResponse) {
                    long requestID = signedResponse.getID();
                    long clientID = (requestID & 0x7FFFFFFF00000000L) >> 32;

                    try {
                        responseLock.lock();
                        // filter out late arriving responses
                        if (processedRequests.containsKey(requestID)
                                || resubmittedRequests.contains(requestID)
                                && signedResponse.getRequestType().equals(RequestType.UNORDERED_REQUEST)) {
                            if (debug_)
                                System.out.println("Request is arriving late - requestID: " + requestID);
                            return;
                        }
                    } finally {
                        responseLock.unlock();
                    }

                    if (role.equals(ReplicaRole.Aggregator)) { // TODO change this when Aggregation ACKs are implemented
                        try {
                            aggregationLock.lock();
                            if (!signatureShares.containsKey(requestID))
                                signatureShares.put(requestID, new HashSet<>());

                            signatureShares.get(requestID).add(new BLSSignature(requestID, signedResponse.getSignedData(),
                                    signedResponse.getSignature(), signedResponse.getRequestType(), signedResponse.getSignerIndex()));

                            // If sufficient signature shares have been received, aggregate the signature
                            boolean ordered = signedResponse.getRequestType().equals(RequestType.ORDERED_REQUEST);
                            checkIfReadyForAggregation(requestID, ordered, false);
                        } finally {
                            aggregationLock.unlock();
                        }
                    } else {
                        // TODO work with aggregation ACKs at this point
                    }
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                }

                @Override
                public void onCompleted() {
                    SignatureAck response = SignatureAck.newBuilder().build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
            };
        }

        public StreamObserver<SignedBatchResponse> submitResponseBatchStream(StreamObserver<SignatureAck> responseObserver) {
            return new StreamObserver<SignedBatchResponse>() {
                @Override
                public void onNext(SignedBatchResponse signedResponse) {
                    int batchSize = signedResponse.getSignaturesCount();
                    List<BLSSignature> signatureShares_ = new ArrayList<>(batchSize);

                    try {
                        responseLock.lock();
                        // filter out late arriving responses
                        for (int i = 0; i < batchSize; i++) {
                            long requestID = signedResponse.getRequestIDs(i);
                            long clientID = (requestID & 0x7FFFFFFF00000000L) >> 32;

                            // TODO currently a batch indicates that the requests are ordered
                            if (processedRequests.containsKey(requestID)) {
                                if (debug_)
                                    System.out.println("Request is arriving late - requestID: " + requestID);
                            } else {
                                signatureShares_.add(new BLSSignature(requestID, signedResponse.getSignedData(i),
                                        signedResponse.getSignatures(i), RequestType.ORDERED_REQUEST, signedResponse.getSignerIndex()));
                            }
                        }
                    } finally {
                        responseLock.unlock();
                    }

                    if (role.equals(ReplicaRole.Aggregator)) { // TODO change this when Aggregation ACKs are implemented
                        try {
                            aggregationLock.lock();
                            for (BLSSignature signatureShare : signatureShares_) {
                                if (!signatureShares.containsKey(signatureShare.requestID()))
                                    signatureShares.put(signatureShare.requestID(), new HashSet<>());

                                signatureShares.get(signatureShare.requestID()).add(signatureShare);

                                // If sufficient signature shares have been received, aggregate the signature
                                checkIfReadyForAggregation(signatureShare.requestID(), true, false);
                            }
                        } finally {
                            aggregationLock.unlock();
                        }
                    } else {
                        // TODO work with aggregation ACKs at this point
                    }
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                }

                @Override
                public void onCompleted() {
                    SignatureAck response = SignatureAck.newBuilder().build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
            };
        }
    }

    private class ConsensusResponseHandler implements Runnable {
        private final ByteBuffer socketBuffer;
        private final String socketAddress;
        private SocketChannel socketChannel;
        private boolean running;

        public ConsensusResponseHandler() {
            this("/tmp/cart-response-collector.socket");
        }

        public ConsensusResponseHandler(String socketAddress) {
            this.socketBuffer = ByteBuffer.allocate(1024 * 1024);
            this.socketAddress = socketAddress;
            this.running = true;
        }

        @Override
        public void run() {
            try {
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                Files.deleteIfExists(Path.of(socketAddress));
                serverSocketChannel.bind(UnixDomainSocketAddress.of(socketAddress));

                System.out.println("ResponseCollector: Waiting for consensus adapter to connect");
                this.socketChannel = serverSocketChannel.accept();
                System.out.println("ResponseCollector: Consensus adapter connected");

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            JNIBinding.init_relic();
            while (running) {
                processResponses();
            }
        }

        private void processResponses() {
            socketBuffer.limit(Integer.BYTES);
            try {
                int bytesRead = this.socketChannel.read(socketBuffer);
                if (bytesRead < 0) {
                    System.err.println("Server-Side CART: ResponseCollector SocketChannel has been terminated");
                    return;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            socketBuffer.flip();
            int header = socketBuffer.getInt();
            boolean ordered = header < 0;
            int payloadLength = header & 0x7FFFFFFF;
            socketBuffer.clear();


            socketBuffer.limit(payloadLength);

            try {
                int bytesRead = 0;
                while (bytesRead < payloadLength) {
                    bytesRead += socketChannel.read(socketBuffer);
                    if (bytesRead < 0) {
                        System.err.println("Server-Side CART: ResponseCollector SocketChannel has been terminated");
                        break;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            if (debug_)
                System.out.println("Server-Side CART: Received " + (ordered ? "ordered " : "unordered ")
                        + "response from the collector socket.");

            socketBuffer.flip();

            int numRequests = socketBuffer.getInt();
            long[] requestIDs = new long[numRequests];
            byte[][] requests = new byte[numRequests][];
            byte[][] responses = new byte[numRequests][];

            // Extract the requests first
            for (int i = 0; i < numRequests; i++) {
                int requestLength = socketBuffer.getInt();
                requestIDs[i] = socketBuffer.getLong();
                requests[i] = new byte[requestLength - Long.BYTES];
                socketBuffer.get(requests[i]);

                if(role.equals(ReplicaRole.Aggregator) && latencyBenchmark.get()) {
                    if(ordered)
                        orderingTime.put(requestIDs[i], System.nanoTime());
                    else
                        readonlyTime.put(requestIDs[i], System.nanoTime());
                }
            }

            // Then extract the responses
            for (int i = 0; i < numRequests; i++) {
                int responseLength = socketBuffer.getInt();
                responses[i] = new byte[responseLength];
                socketBuffer.get(responses[i]);
            }

            if (ordered) {
                long batchID = currentBatchNumber.incrementAndGet();
                try {
                    batchLock.lock();
                    receivedBatchLookup.put(batchID, requestIDs);
                } finally {
                    batchLock.unlock();
                }

                if (debug_)
                    System.out.println("Server-Side CART: Creating BatchSignatureRequest for batchID " + batchID);

                SignatureRequest signatureRequest = new BatchSignatureRequest(batchID, requestIDs,
                        RequestType.ORDERED_REQUEST, requests, responses);
                signatureHandler.signAndSubmit(signatureRequest);
            } else {
                for (int i = 0; i < numRequests; i++) {
                    if (debug_)
                        System.out.println("Server-Side CART: Creating SignatureRequest for requestID " + requestIDs[i]);

                    SignatureRequest signatureRequest = new SingleSignatureRequest(requestIDs[i],
                            RequestType.UNORDERED_REQUEST, requests[i], responses[i]);
                    signatureHandler.signAndSubmit(signatureRequest);
                }
            }

            try {
                responseLock.lock();
                for (int i = 0; i < numRequests; i++) {
                    if (!responseBuilder.containsKey(requestIDs[i])) {
                        responseBuilder.put(requestIDs[i], ServiceResponse.newBuilder()
                                .setId(requestIDs[i])
                                .setRequestType(ordered ? RequestType.ORDERED_REQUEST : RequestType.UNORDERED_REQUEST));
                    } else if (!responseBuilder.get(requestIDs[i]).getRequestType()
                            .equals(ordered ? RequestType.ORDERED_REQUEST : RequestType.UNORDERED_REQUEST)) {
                        resubmittedRequests.add(requestIDs[i]);
                    }
                    responseBuilder.get(requestIDs[i])
                            .setRequestType(ordered ? RequestType.ORDERED_REQUEST : RequestType.UNORDERED_REQUEST)
                            .setResponse(ByteString.copyFrom(responses[i]));

                    if (role.equals(ReplicaRole.Aggregator))
                        verifyAndReply(requestIDs[i]);
                }
            } finally {
                responseLock.unlock();
            }

            //}
            socketBuffer.clear();
        }

        public void shutdown() {
            this.running = false;
            try {
                this.socketChannel.close();
            } catch (IOException e) {
                System.err.println("Error while closing the result SocketChannel");
            }
        }
    }

    private class AggregationHandler implements Runnable {
        private final LinkedBlockingQueue<AggregationRequest> pendingAggregations;
        private final LinkedBlockingQueue<AggregatedSignature> completedAggregations;
        private final int aggregationBufferSize;

        private final HashMap<Integer, HashMap<Long, AggregatedSignature>> aggregatedSignatures;
        private final HashMap<Integer, ReentrantLock> aggregatedSignatureLocks;
        private final HashMap<Integer, Set<Long>> processedSignatures;
        private int numAggregatedBatchSignatures;

        private boolean running;

        public AggregationHandler(int aggregationBufferSize, int numThreads, int[] thresholds) {
            this.pendingAggregations = new LinkedBlockingQueue<>();
            this.completedAggregations = new LinkedBlockingQueue<>();
            this.aggregationBufferSize = aggregationBufferSize;
            this.numAggregatedBatchSignatures = 0;

            this.aggregatedSignatures = new HashMap<>();
            this.aggregatedSignatureLocks = new HashMap<>();
            this.processedSignatures = new HashMap<>();

            for (int threshold : thresholds) {
                this.aggregatedSignatures.put(threshold, new HashMap<>());
                this.aggregatedSignatureLocks.put(threshold, new ReentrantLock(true));
                this.processedSignatures.put(threshold, new HashSet<>());
            }

            this.running = true;

            ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
            for (int i = 0; i < numThreads; i++) {
                executorService.execute(() -> {
                    JNIBinding.init_relic();
                    while (running) {
                        try {
                            AggregationRequest aggregationRequest = pendingAggregations.take();

                            long requestID = aggregationRequest.requestID();
                            ByteString majorityResponse = aggregationRequest.majorityResponse();

                            try {
                                aggregationLock.lock();
                                byte[] aggregatedSignature = null;
                                int numShares = aggregationRequest.signatureShares().size();
                                byte[][] signatures = new byte[numShares][];
                                int[] indices = new int[numShares];
                                int threshold = aggregationRequest.requestType() == RequestType.ORDERED_REQUEST ? orderedThreshold : unorderedThreshold;
                                int strategy = tentativeAggregation ? VerificationStrategy.NON_VERIFIED.ordinal()
                                        : VerificationStrategy.OPTIMISTIC.ordinal();

                                int index = 0;
                                for (BLSSignature share : aggregationRequest.signatureShares()) {
                                    if (share.signedData().equals(majorityResponse) && share.requestType().equals(aggregationRequest.requestType())) {
                                        byte[] signatureShare = share.signature().toByteArray();
                                        signatures[index] = signatureShare;
                                        indices[index] = share.signerIndex();
                                        index++;
                                    }
                                }

                                if (debug_)
                                    System.out.println("Requesting aggregation for requestID " + requestID);

                                aggregatedSignature = JNIBinding.aggregate(signatures, indices,
                                        majorityResponse.toByteArray(), threshold, strategy);

                                if (aggregatedSignature != null) {
                                    ByteString aggregatedSignature_ = ByteString.copyFrom(aggregatedSignature);
                                    aggregationInProgress.remove(requestID);
                                    signedRequests.remove(requestID);

                                    if(latencyBenchmark.get()) {
                                        if(batchSignatures && threshold == orderedThreshold) {
                                            try {
                                                batchLock.lock();
                                                for (long ID : receivedBatchLookup.get(requestID))
                                                    aggregationTime.put(ID, System.nanoTime());
                                            } finally {
                                                batchLock.unlock();
                                            }
                                        } else {
                                            aggregationTime.put(requestID, System.nanoTime());
                                        }
                                    }

                                    AggregatedSignature signature = new AggregatedSignature(requestID, aggregationRequest.requestType(), aggregatedSignature_, majorityResponse, indices);
                                    completedAggregations.put(signature);

                                    if (tentativeAggregation) {
                                        try {
                                            aggregatedSignatureLocks.get(threshold).lock();
                                            if (batchSignatures && threshold == orderedThreshold) {
                                                try {
                                                    batchLock.lock();
                                                    numAggregatedBatchSignatures += receivedBatchLookup.get(requestID).length;
                                                } finally {
                                                    batchLock.unlock();
                                                }
                                            }
                                            aggregatedSignatures.get(threshold).put(requestID, signature);
                                        } finally {
                                            aggregatedSignatureLocks.get(threshold).unlock();
                                        }
                                    } else {
                                        signatureShares.remove(requestID);
                                    }
                                } else {
                                    StringBuilder debugString = new StringBuilder();
                                    debugString.append("Aggregation was not successful for requestID ").append(requestID);
                                    for (var share : aggregationRequest.signatureShares()) {
                                        debugString.append("\nIndex: ").append(share.signerIndex()).append(" - signed data: ").append(share.signedData());
                                        if (share.signature() == null || share.signature().equals(ByteString.EMPTY))
                                            System.out.println("Signature of share index " + share.signerIndex() + " is null or empty");
                                    }

                                    System.err.println(debugString.toString());
                                    // TODO in this case the aggregation has to be retried since it was not successful
                                }
                            } finally {
                                aggregationLock.unlock();
                            }

                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        }

        public void submitAggregationTask(AggregationRequest request) {
            try {
                pendingAggregations.put(request);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void shutdown() {
            // TODO clear LinkedBlockingQueue
            this.running = false;
        }

        @Override
        public void run() {
            JNIBinding.init_relic();
            while (running) {
                try {
                    AggregatedSignature aggregatedSignature = completedAggregations.take();
                    try {
                        responseLock.lock();
                        if (batchSignatures && aggregatedSignature.requestType().equals(RequestType.ORDERED_REQUEST)) {
                            long batchID = aggregatedSignature.requestID();
                            try {
                                batchLock.lock();
                                if (receivedBatchLookup.get(batchID) == null) {
                                    System.out.println("batchID " + batchID + " is not present");
                                }
                                for (long requestID : receivedBatchLookup.get(batchID)) {
                                    responseBuilder.get(requestID)
                                            .setSignature(aggregatedSignature.signature());
                                    verifyAndReply(requestID);
                                }
                                if (!tentativeAggregation) {
                                    receivedBatchLookup.remove(batchID);
                                }
                            } finally {
                                batchLock.unlock();
                            }
                        } else {
                            long requestID = aggregatedSignature.requestID();
                            responseBuilder.get(requestID)
                                    .setSignature(aggregatedSignature.signature());
                            verifyAndReply(requestID);
                        }
                    } finally {
                        responseLock.unlock();
                    }

                    int threshold = aggregatedSignature.requestType().equals(RequestType.ORDERED_REQUEST)
                            ? orderedThreshold : unorderedThreshold;

                    boolean validateDeferredSignatures = tentativeAggregation && (batchSignatures
                            && aggregatedSignature.requestType().equals(RequestType.ORDERED_REQUEST)
                            ? numAggregatedBatchSignatures >= aggregationBufferSize
                            : aggregatedSignatures.get(threshold).size() >= aggregationBufferSize);

                    if (validateDeferredSignatures) {
                        byte[][] signatures_ = null;
                        byte[][] data_ = null;
                        try {
                            aggregatedSignatureLocks.get(threshold).lock();
                            if (debug_)
                                System.out.println("Starting batch verification for "
                                        + (threshold == orderedThreshold ? "ordered " : "unordered ") + "requests");

                            if (byzantineAggregator && attackCounter.get() >= attackStartingPoint + attackDuration) {
                                System.out.println("Stopping Byzantine behavior now");
                                byzantineAggregator = false;
                            }

                            signatures_ = new byte[aggregatedSignatures.get(threshold).size()][];
                            data_ = new byte[aggregatedSignatures.get(threshold).size()][];

                            int i = 0;
                            for (long requestID : aggregatedSignatures.get(threshold).keySet()) {
                                signatures_[i] = aggregatedSignatures.get(threshold).get(requestID).signature().toByteArray();
                                data_[i] = aggregatedSignatures.get(threshold).get(requestID).signedData().toByteArray();
                                processedSignatures.get(threshold).add(requestID);
                                i++;
                            }
                        } finally {
                            aggregatedSignatureLocks.get(threshold).unlock();
                        }

                        if (!JNIBinding.aggregateVerify(data_, signatures_, 0, threshold)) {
                            System.out.println("Aggregated verification was not successful");
                            HashSet<Integer> maliciousReplicas = discloseByzantineReplicas(threshold);
                            HashSet<Long> possiblyCorruptedSignatures = new HashSet<>();
                            try {
                                aggregatedSignatureLocks.get(threshold).lock();
                                for (long requestID : aggregatedSignatures.get(threshold).keySet()) {
                                    AggregatedSignature signature = aggregatedSignatures.get(threshold).get(requestID);
                                    long numCorruptedShares = Arrays.stream(signature.shareIndices()).filter(index -> maliciousReplicas.contains(index - 1)).count();
                                    if (numCorruptedShares > 0) {
                                        possiblyCorruptedSignatures.add(requestID);
                                    }
                                }
                            } finally {
                                aggregatedSignatureLocks.get(threshold).unlock();
                            }

                            HashMap<Long, byte[]> recoveredSignatures = new HashMap<>();
                            try {
                                aggregationLock.lock();
                                for (long requestID : possiblyCorruptedSignatures) {
                                    int numShares = signatureShares.get(requestID).size() - maliciousReplicas.size();
                                    if (numShares >= threshold) {
                                        byte[][] signatures = new byte[numShares][];
                                        int[] indices = new int[numShares];
                                        int strategy = VerificationStrategy.OPTIMISTIC.ordinal();

                                        int index = 0;
                                        for (BLSSignature share : signatureShares.get(requestID)) {
                                            if (!maliciousReplicas.contains(share.signerIndex() - 1)) {
                                                byte[] signatureShare = share.signature().toByteArray();
                                                if (signatureShare != null) {
                                                    signatures[index] = signatureShare;
                                                } else {
                                                    throw new RuntimeException("RequestID " + requestID + ": Signature Share with signerIndex " + share.signerIndex() + " is null");
                                                }

                                                indices[index] = share.signerIndex();
                                                index++;
                                            }
                                        }
                                        try {
                                            aggregatedSignatureLocks.get(threshold).lock();
                                            byte[] recoveredSignature = JNIBinding.aggregate(signatures, indices,
                                                    aggregatedSignatures.get(threshold).get(requestID).signedData().toByteArray(), threshold, strategy);
                                            recoveredSignatures.put(requestID, recoveredSignature);
                                        } finally {
                                            aggregatedSignatureLocks.get(threshold).unlock();
                                        }
                                    }
                                }

                                // Send the recovered signatures back to the clients
                                try {
                                    responseLock.lock();
                                    for (long requestID : recoveredSignatures.keySet()) {
                                        if (recoveredSignatures.get(requestID) != null) {
                                            responseBuilder.get(requestID).setSignature(ByteString.copyFrom(recoveredSignatures.get(requestID)));
                                            verifyAndReply(requestID);
                                        }
                                    }
                                } finally {
                                    responseLock.unlock();
                                }
                            } finally {
                                aggregationLock.unlock();
                            }
                        }
                        clearAggregatedSignatures(threshold);
                        // TODO clear the batchLookUp Table at this point
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

            }
        }

        private void clearAggregatedSignatures(int threshold) {
            try {
                aggregatedSignatureLocks.get(threshold).lock();
                try {
                    responseLock.lock();
                    // Clear the aggregated signatures first
                    for (long ID : processedSignatures.get(threshold)) {
                        aggregatedSignatures.get(threshold).remove(ID);
                        if (processedRequests.containsKey(ID) && (processedRequests.get(ID).equals(ResponseStatus.TENTATIVE)
                                || processedRequests.get(ID).equals(ResponseStatus.DEFINITIVE))) {
                            if (batchSignatures && threshold == orderedThreshold) {
                                try {
                                    batchLock.lock();
                                    for (long requestID : receivedBatchLookup.get(ID))
                                        responseBuilder.remove(ID);

                                    receivedBatchLookup.remove(ID);
                                } finally {
                                    batchLock.unlock();
                                }
                            } else {
                                responseBuilder.remove(ID);
                            }
                        }
                    }
                } finally {
                    responseLock.unlock();
                }
                numAggregatedBatchSignatures = 0;
            } finally {
                aggregatedSignatureLocks.get(threshold).unlock();
            }

            // Clear the corresponding signature shares now
            try {
                aggregationLock.lock();
                for (long ID : processedSignatures.get(threshold))
                    signatureShares.remove(ID);

                if(debug_)
                    System.out.println("Removed " + processedSignatures.get(threshold).size()
                        + " signature share entries while " + signatureShares.size() + " entries are remaining");
            } finally {
                aggregationLock.unlock();
            }

            processedSignatures.get(threshold).clear();
        }

        public HashSet<Integer> discloseByzantineReplicas(int threshold) {
            HashSet<Integer> maliciousReplicaIDs = new HashSet<>();
            try {
                aggregationLock.lock();
                try {
                    aggregatedSignatureLocks.get(threshold).lock();
                    HashMap<Integer, Integer> numSignatureShares = new HashMap<>();
                    HashMap<Integer, HashSet<Long>> signedRequests = new HashMap<>();
                    for (int i = 0; i < numReplicas; i++) {
                        numSignatureShares.put(i, 0);
                        signedRequests.put(i, new HashSet<>());
                    }

                    for (long requestID : processedSignatures.get(threshold)) {
                        for (BLSSignature signatureShare : signatureShares.get(requestID)) {
                            int replicaID = signatureShare.signerIndex() - 1;
                            numSignatureShares.put(replicaID, numSignatureShares.get(replicaID) + 1);
                            signedRequests.get(replicaID).add(requestID);
                        }
                    }

                    for (int replicaID : numSignatureShares.keySet()) {
                        byte[][] signatures = new byte[numSignatureShares.get(replicaID)][];
                        byte[][] signedData = new byte[numSignatureShares.get(replicaID)][];

                        int index = 0;
                        for (long requestID : signedRequests.get(replicaID)) {
                            BLSSignature signatureShare = signatureShares.get(requestID).stream()
                                    .filter(s -> s.signerIndex() == replicaID + 1).findFirst().orElseThrow();

                            signatures[index] = signatureShare.signature().toByteArray();
                            signedData[index] = signatureShare.signedData().toByteArray();
                            index++;
                        }

                        if (!JNIBinding.aggregateVerify(signedData, signatures, replicaID + 1, threshold)) {
                            maliciousReplicaIDs.add(replicaID);
                            System.out.println("Replica " + replicaID + " found to be malicious");
                        }
                    }
                } finally {
                    aggregatedSignatureLocks.get(threshold).unlock();
                }
            } finally {
                aggregationLock.unlock();
            }
            return maliciousReplicaIDs;
        }
    }

    private class SignatureHandler {
        private boolean running;
        private final LinkedBlockingQueue<SignatureRequest> pendingSignatures;
        private final LinkedBlockingQueue<SignedResponse> completedSignatures;
        private final LinkedBlockingQueue<SignedBatchResponse> completedBatchSignatures;
        private final ExecutorService executorService;

        private final HashMap<Integer, ResponseCollectorGrpc.ResponseCollectorStub> collectorStubs;
        private final HashMap<Integer, StreamObserver<SignedResponse>> collectorRequestObservers;
        private final HashMap<Integer, StreamObserver<SignedBatchResponse>> collectorBatchRequestObservers;

        public SignatureHandler(int numSignThreads) {
            this.running = true;
            this.pendingSignatures = new LinkedBlockingQueue<>();
            this.completedSignatures = new LinkedBlockingQueue<>();
            this.completedBatchSignatures = new LinkedBlockingQueue<>();

            this.collectorStubs = new HashMap<>();
            this.collectorRequestObservers = new HashMap<>();
            this.collectorBatchRequestObservers = new HashMap<>();

            hostInfo.forEach((hostID, hostAddress) -> {
                if (hostID != replicaID) {
                    ManagedChannel collectorChannel = ManagedChannelBuilder.forTarget(hostAddress + ":" + (DEFAULT_COLLECTOR_PORT)).usePlaintext().build();
                    collectorStubs.put(hostID, ResponseCollectorGrpc.newStub(collectorChannel).withWaitForReady());
                    collectorRequestObservers.put(hostID, collectorStubs.get(hostID).submitResponseStream(new StreamObserver<SignatureAck>() {
                        @Override
                        public void onNext(SignatureAck signatureAck) {
                            System.out.println("Collector stream has been terminated with a final message");
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            System.err.println("Collector stream: An error has occurred");
                            throwable.printStackTrace();
                        }

                        @Override
                        public void onCompleted() {
                            System.out.println("Collector stream has been terminated");
                        }
                    }));

                    collectorBatchRequestObservers.put(hostID, collectorStubs.get(hostID).submitResponseBatchStream(new StreamObserver<SignatureAck>() {
                        @Override
                        public void onNext(SignatureAck signatureAck) {
                            System.out.println("Collector stream has been terminated with a final message");
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            System.err.println("Collector stream: An error has occurred");
                            throwable.printStackTrace();
                        }

                        @Override
                        public void onCompleted() {
                            System.out.println("Collector stream has been terminated");
                        }
                    }));
                }
            });

            this.executorService = Executors.newFixedThreadPool(numSignThreads + 2);
            for (int t = 0; t < numSignThreads; t++) {
                executorService.execute(() -> {
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        Random random = new Random(System.currentTimeMillis());
                        JNIBinding.init_relic();
                        while (running) {
                            SignatureRequest signatureRequest = pendingSignatures.take();
                            if (signatureRequest instanceof SingleSignatureRequest request) {
                                RequestType requestType = request.requestType();
                                boolean ordered = requestType.equals(RequestType.ORDERED_REQUEST);

                                long requestID = request.requestID();
                                byte[] requestData = request.requestData();
                                byte[] responseData = request.responseData();

                                ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                                buffer.putLong(requestID);
                                digest.update(buffer.array());
                                digest.update(requestData);
                                digest.update(responseData);

                                byte[] hashedData = digest.digest();
                                ByteString hashedData_ = ByteString.copyFrom(hashedData);

                                // Generate a random signature if this replica is supposed to be malicious
                                if (byzantineAggregator && attackCounter.get() >= attackStartingPoint && role.equals(ReplicaRole.Aggregator) && random.nextDouble() <= byzantineAttackRate) {
                                    System.out.println("Tampering with signature for requestID " + requestID);
                                    random.nextBytes(hashedData);
                                }

                                ByteString signature = ByteString.copyFrom(JNIBinding.sign(hashedData, ordered ? orderedThreshold : unorderedThreshold));
                                SignedResponse signedResponse = SignedResponse.newBuilder()
                                        .setID(requestID)
                                        .setRequestType(requestType)
                                        .setSignedData(hashedData_)
                                        .setSignerIndex(replicaID + 1)
                                        .setSignature(signature)
                                        .setBatchSignature(false)
                                        .build();

                                completedSignatures.put(signedResponse);

                                if(role.equals(ReplicaRole.Aggregator) && latencyBenchmark.get())
                                    signatureTime.put(requestID, System.nanoTime());

                                if (debug_)
                                    System.out.println("Response for requestID " + requestID + " has been signed and submitted");

                                if (role.equals(ReplicaRole.Aggregator)) {
                                    try {
                                        aggregationLock.lock();
                                        // TODO change this and store the signature shares until an Aggregation ACK arrives
                                        if (!signatureShares.containsKey(requestID))
                                            signatureShares.put(requestID, new HashSet<>());
                                        signedRequests.put(requestID, requestType);
                                        signatureShares.get(requestID).add(new BLSSignature(requestID, hashedData_, signature, requestType, replicaID + 1));

                                        checkIfReadyForAggregation(requestID, ordered, false);
                                    } finally {
                                        aggregationLock.unlock();
                                    }
                                }
                            } else if (signatureRequest instanceof BatchSignatureRequest request && !batchSignatures) {
                                RequestType requestType = request.requestType();
                                SignedBatchResponse.Builder signedBatchResponse = SignedBatchResponse.newBuilder()
                                        .setSignerIndex(replicaID + 1);

                                int numRequests = request.requestIDs().length;
                                ByteString[] hashedData = new ByteString[numRequests];
                                ByteString[] signatures = new ByteString[numRequests];

                                for (int i = 0; i < numRequests; i++) {
                                    long requestID = request.requestIDs()[i];
                                    byte[] requestData = request.requestData()[i];
                                    byte[] responseData = request.responseData()[i];

                                    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                                    buffer.putLong(requestID);
                                    digest.update(buffer.array());
                                    digest.update(requestData);
                                    digest.update(responseData);
                                    byte[] hashedData_ = digest.digest();

                                    hashedData[i] = ByteString.copyFrom(hashedData_);
                                    // Generate a random signature if this replica is supposed to be malicious
                                    if (byzantineAggregator && attackCounter.get() >= attackStartingPoint
                                            && role.equals(ReplicaRole.Aggregator) && random.nextDouble() <= byzantineAttackRate) {
                                        System.out.println("Tampering with the data for requestID " + requestID);
                                        random.nextBytes(hashedData_);
                                    }

                                    signedBatchResponse.addRequestIDs(request.requestIDs()[i]).addSignedData(hashedData[i]);

                                    signatures[i] = ByteString.copyFrom(JNIBinding.sign(hashedData_, orderedThreshold));
                                    signedBatchResponse.addSignatures(signatures[i]);
                                }

                                completedBatchSignatures.put(signedBatchResponse.build());

                                if(role.equals(ReplicaRole.Aggregator) && latencyBenchmark.get())
                                    for(long requestID : request.requestIDs())
                                        signatureTime.put(requestID, System.nanoTime());

                                if (role.equals(ReplicaRole.Aggregator)) {
                                    try {
                                        aggregationLock.lock();
                                        // TODO change this and store the signature shares until an Aggregation ACK arrives
                                        for (int i = 0; i < numRequests; i++) {
                                            long requestID = request.requestIDs()[i];
                                            if (!signatureShares.containsKey(requestID))
                                                signatureShares.put(requestID, new HashSet<>());
                                            signedRequests.put(requestID, requestType);
                                            signatureShares.get(requestID).add(new BLSSignature(requestID, hashedData[i],
                                                    signatures[i], requestType, replicaID + 1));

                                            checkIfReadyForAggregation(requestID, true, false);
                                        }
                                    } finally {
                                        aggregationLock.unlock();
                                    }
                                }
                            } else if (signatureRequest instanceof BatchSignatureRequest request && batchSignatures) {
                                long batchID = ((BatchSignatureRequest) signatureRequest).batchID();
                                int numRequests = request.requestIDs().length;
                                ByteString[] hashedData = new ByteString[numRequests];

                                for (int i = 0; i < numRequests; i++) {
                                    long requestID = request.requestIDs()[i];
                                    byte[] requestData = request.requestData()[i];
                                    byte[] responseData = request.responseData()[i];

                                    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                                    buffer.putLong(requestID);
                                    digest.update(buffer.array());
                                    digest.update(requestData);
                                    digest.update(responseData);
                                    byte[] hashedData_ = digest.digest();

                                    hashedData[i] = ByteString.copyFrom(hashedData_);
                                    // Generate a random signature if this replica is supposed to be malicious
                                    if (byzantineAggregator && attackCounter.get() >= attackStartingPoint
                                            && role.equals(ReplicaRole.Aggregator) && random.nextDouble() <= byzantineAttackRate) {
                                        System.out.println("Tampering with the data for requestID " + requestID);
                                        random.nextBytes(hashedData_);
                                    }
                                }

                                for (int i = 0; i < numRequests; i++) {
                                    digest.update(hashedData[i].toByteArray());
                                }
                                byte[] hashedBatchData = digest.digest();
                                ByteString hashedDataBytes = ByteString.copyFrom(hashedBatchData);
                                ByteString signature = ByteString.copyFrom(JNIBinding.sign(hashedBatchData, orderedThreshold));
                                SignedResponse signedResponse = SignedResponse.newBuilder()
                                        .setSignerIndex(replicaID + 1)
                                        .setID(batchID)
                                        .setBatchSignature(true)
                                        .setSignedData(hashedDataBytes)
                                        .setSignature(signature)
                                        .build();

                                completedSignatures.put(signedResponse);

                                if(role.equals(ReplicaRole.Aggregator) && latencyBenchmark.get())
                                    for(long requestID : request.requestIDs())
                                        signatureTime.put(requestID, System.nanoTime());

                                try {
                                    responseLock.lock();
                                    for (int i = 0; i < numRequests; i++) {
                                        responseBuilder.get(request.requestIDs()[i])
                                                .addAllAuxiliaryData(Arrays.stream(hashedData).toList())
                                                .setAuxiliaryIndex(i);
                                    }
                                } finally {
                                    responseLock.unlock();
                                }

                                if (role.equals(ReplicaRole.Aggregator)) {
                                    try {
                                        aggregationLock.lock();
                                        // TODO change this and store the signature shares until an Aggregation ACK arrives
                                        if (!signatureShares.containsKey(batchID))
                                            signatureShares.put(batchID, new HashSet<>());

                                        signedRequests.put(batchID, RequestType.ORDERED_REQUEST);
                                        signatureShares.get(batchID).add(new BLSSignature(batchID, hashedDataBytes,
                                                signature, RequestType.ORDERED_REQUEST, replicaID + 1));


                                        checkIfReadyForAggregation(batchID, true, false);
                                    } finally {
                                        aggregationLock.unlock();
                                    }
                                }
                            } else {
                                throw new RuntimeException("Unknown SignatureRequest type received");
                            }
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            executorService.execute(() -> {
                while (running) {
                    try {
                        SignedResponse signedResponse = completedSignatures.take();
                        if (debug_)
                            System.out.println("Server-Side CART: Signature for requestID " + signedResponse.getID() + " has been created and will be distributed");
                        collectorStubs.forEach((id, stub) -> {
                            collectorRequestObservers.get(id).onNext(signedResponse);
                        });
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            executorService.execute(() -> {
                while (running) {
                    try {
                        SignedBatchResponse signedBatchResponse = completedBatchSignatures.take();
                        if (debug_)
                            System.out.println("Server-Side CART: Signature for batch starting with requestID " + signedBatchResponse.getRequestIDs(0) + " has been created and will be distributed");
                        collectorStubs.forEach((id, stub) -> {
                            collectorBatchRequestObservers.get(id).onNext(signedBatchResponse);
                        });
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        public void shutdown() {
            this.running = false;
            this.executorService.shutdownNow();
        }

        public void signAndSubmit(SignatureRequest request) {
            try {
                pendingSignatures.put(request);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }


    private void startThroughputBenchmark() {
        System.out.println("Initializing server-side throughput benchmark");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        this.throughput = new ArrayList<>(benchmarkDuration / measurementInterval);
        intervalStart = System.currentTimeMillis();

        scheduler.scheduleAtFixedRate(() -> {
            double currentTime = System.currentTimeMillis();
            long numRequests = requestCounter.getAndSet(0);
            double currentThroughput = numRequests * 1000.0 / measurementInterval;
            throughput.add(currentThroughput);

            if (currentTime - intervalStart >= benchmarkDuration) {
                double average = throughput.stream().mapToDouble(e -> e).average().orElse(-1);
                System.out.println("Average throughput " + average + " req/s over the last " + benchmarkDuration + "ms");
                throughput.clear();
                intervalStart = currentTime;
            }
        }, 0, measurementInterval, TimeUnit.MILLISECONDS);
    }

    private void startLatencyBenchmark() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Start the latency benchmark
        scheduler.schedule(() -> {
            System.out.println("Initializing server-side latency benchmark");
            latencyBenchmark.set(true);
        }, 21000, TimeUnit.MILLISECONDS);

        // Stop the latency benchmark;
        scheduler.schedule(() -> {
            latencyBenchmark.set(false);

            System.out.println("Stopping latency benchmark and evaluating results");
            long totalOrderingTime = 0;
            long totalReadonlyTime = 0;
            long totalSigningTime = 0;
            long totalDistributionTime = 0;
            long totalAggregationTime = 0;
            long totalReplyTime = 0;

            int numRequests = 0;
            int numOrdered = 0;
            int numUnordered = 0;
            for(long requestID : replyTime.keySet()) {
                if(arrivalTime.get(requestID) == null || (orderingTime.get(requestID) == null
                        && readonlyTime.get(requestID) == null) || signatureTime.get(requestID) == null
                        || distributionTime.get(requestID) == null || aggregationTime.get(requestID) == null) {
                    System.out.println("Skipping requestID " + requestID + " because not all data is available");
                    continue;
                }
                if(orderingTime.containsKey(requestID)) {
                    totalOrderingTime += orderingTime.get(requestID) - arrivalTime.get(requestID);
                    totalSigningTime += signatureTime.get(requestID) - orderingTime.get(requestID);
                    numOrdered++;
                } else {
                    totalReadonlyTime += readonlyTime.get(requestID) - arrivalTime.get(requestID);
                    totalSigningTime += signatureTime.get(requestID) - readonlyTime.get(requestID);
                    numUnordered++;
                }
                totalDistributionTime += distributionTime.get(requestID) - signatureTime.get(requestID);
                totalAggregationTime += aggregationTime.get(requestID) - distributionTime.get(requestID);
                totalReplyTime += replyTime.get(requestID) - aggregationTime.get(requestID);
                numRequests++;
            }

            double averageOrderedProcessingTime = totalOrderingTime / (1000.0 * numOrdered)
                    + totalSigningTime / (1000.0 * numRequests)
                    + totalDistributionTime / (1000.0 * numRequests)
                    + totalAggregationTime / (1000.0 * numRequests)
                    + totalReplyTime / (1000.0 * numRequests);

            double averageUnorderedProcessingTime = totalReadonlyTime / (1000.0 * numUnordered)
                    + totalSigningTime / (1000.0 * numRequests)
                    + totalDistributionTime / (1000.0 * numRequests)
                    + totalAggregationTime / (1000.0 * numRequests)
                    + totalReplyTime / (1000.0 * numRequests);

            System.out.println("Latency Results: " +
                    "\n Num Requests: " + numRequests +
                    "\n Average Ordering Time: " + (totalOrderingTime / (1000.0 * numOrdered)) + " us" +
                    "\n Average Readonly Time: " + (totalReadonlyTime / (1000.0 * numUnordered)) + " us" +
                    "\n Average Signing Time: " + (totalSigningTime / (1000.0 * numRequests)) + " us" +
                    "\n Average Distribution Time: " + (totalDistributionTime / (1000.0 * numRequests)) + " us" +
                    "\n Average Aggregation Time: " + (totalAggregationTime / (1000.0 * numRequests)) + " us" +
                    "\n Average Reply Time: " + (totalReplyTime / (1000.0 * numRequests)) + " us" +
                    "\n Average Ordered Processing Time: " + averageOrderedProcessingTime +
                    "\n Average Unordered Processing Time: " + averageUnorderedProcessingTime);


        }, 36000, TimeUnit.MILLISECONDS);
    }

    private void checkIfReadyForAggregation(long requestID, boolean ordered, boolean debug) {
        boolean readyForAggregation = false;
        try {
            aggregationLock.lock();

            if (signatureShares.get(requestID) == null) {
                System.out.println("checkIfReadyForAggregation: There are no signatures present for requestID " + requestID);
                return;
            }

            RequestType requestType = ordered ? RequestType.ORDERED_REQUEST : RequestType.UNORDERED_REQUEST;
            long numSignaturesPresent = signatureShares.get(requestID).stream()
                    .filter(s -> s.requestType().equals(ordered ? RequestType.ORDERED_REQUEST : RequestType.UNORDERED_REQUEST)).count();
            try {
                responseLock.lock();
                readyForAggregation = !processedRequests.containsKey(requestID)
                        && (numSignaturesPresent >= (ordered ? orderedThreshold : unorderedThreshold))
                        && signedRequests.containsKey(requestID)
                        && signedRequests.get(requestID).equals(requestType)
                        && !aggregationInProgress.contains(requestID);

                if (debug) {
                    System.out.println("RequestID " + requestID + " is" + (!readyForAggregation ? " not" : "") + " ready for aggregation."
                            + "\nOrdered? " + ordered
                            + "\nProcessed requests: " + processedRequests.containsKey(requestID)
                            + "\nNum Signature Shares: " + numSignaturesPresent
                            + "\nResponse has been signed: " + signedRequests.containsKey(requestID)
                            + "\nAggregation running: " + aggregationInProgress.contains(requestID));
                }
            } finally {
                responseLock.unlock();
            }

            if (!readyForAggregation || debug)
                return;

            int majorityCounter = 0;
            ByteString majorityResponse = null;
            if (!ordered) {
                // Count the number of matching responses
                HashMap<ByteString, Integer> matchingResponses = new HashMap<>();
                for (BLSSignature share : signatureShares.get(requestID)) {
                    if (!matchingResponses.containsKey(share.signedData())) {
                        matchingResponses.put(share.signedData(), 1);
                    } else {
                        int counter = matchingResponses.get(share.signedData()) + 1;
                        majorityCounter = Math.max(counter, majorityCounter);
                        majorityResponse = share.signedData();
                        matchingResponses.put(share.signedData(), counter);
                    }
                }
            } else if (resubmittedRequests.contains(requestID)) {
                if (debug_)
                    System.out.println("Request " + requestID + " has been resubmitted");

                majorityResponse = signatureShares.get(requestID).stream()
                        .filter(s -> s.requestType().equals(RequestType.ORDERED_REQUEST)
                                && s.signerIndex() == replicaID + 1)
                        .findFirst().get().signedData();
            } else {
                majorityResponse = signatureShares.get(requestID).stream()
                        .filter(s -> s.signerIndex() == replicaID + 1).findFirst().get().signedData();
            }

            if (ordered || majorityCounter >= unorderedThreshold) {
                if (debug_)
                    System.out.println("Request " + requestID + ": ready for aggregation");

                if(latencyBenchmark.get()) {
                    if(batchSignatures && ordered) {
                        try {
                            batchLock.lock();
                            for (long ID : receivedBatchLookup.get(requestID))
                                distributionTime.put(ID, System.nanoTime());
                        } finally {
                            batchLock.unlock();
                        }
                    } else {
                        distributionTime.put(requestID, System.nanoTime());
                    }
                }

                AggregationRequest aggregationRequest = new AggregationRequest(requestID,
                        ordered ? RequestType.ORDERED_REQUEST : RequestType.UNORDERED_REQUEST, majorityResponse,
                        signatureShares.get(requestID));
                aggregationHandler.submitAggregationTask(aggregationRequest);
                aggregationInProgress.add(requestID);
            } else if (debug_) {
                StringBuilder debugString = new StringBuilder();
                debugString.append("Request " + requestID + " is ready for aggregation but majority counter is only: " + majorityCounter);
                for (BLSSignature sig : signatureShares.get(requestID))
                    debugString.append("\nSigned data: ").append(sig.signedData());
                System.out.println(debugString.toString());
            }
        } finally {
            aggregationLock.unlock();
        }
    }

    private void verifyAndReply(long requestID) {
        if (firstRequest.compareAndSet(false, true)) {
            if(runThroughputBenchmark)
                startThroughputBenchmark();
            if(runLatencyBenchmark)
                startLatencyBenchmark();
        }


        long clientID = (requestID & 0x7FFFFFFF00000000L) >> 32;
        try {
            responseLock.lock();
            boolean processed = processedRequests.containsKey(requestID);
            boolean responded = processed && (processedRequests.get(requestID).equals(ResponseStatus.TENTATIVE)
                    || processedRequests.get(requestID).equals(ResponseStatus.DEFINITIVE));

            if (!responded) {
                ServiceResponse.Builder builder = responseBuilder.get(requestID);
                if (builder.getRequestType().equals(RequestType.ORDERED_REQUEST) && batchSignatures)
                    builder.setResponseType(ResponseType.BATCH_RESPONSE);
                else
                    builder.setResponseType(ResponseType.SINGLE_RESPONSE);

                if (!processed) {
                    if (!builder.getResponse().isEmpty() && !builder.getSignature().isEmpty()) {
                        if (!clientObservers.containsKey(clientID)) {
                            processedRequests.put(requestID, ResponseStatus.UNPROCESSED);
                        } else {
                            ServiceResponse response = builder.build();
                            replyAndCleanup(clientID, requestID, response, tentativeAggregation);
                        }
                    }
                } else {
                    ServiceResponse response = builder.build();
                    replyAndCleanup(clientID, requestID, response, tentativeAggregation);
                }
            }
        } finally {
            responseLock.unlock();
        }
    }

    private void replyAndCleanup(long clientID, long requestID, ServiceResponse serviceResponse, boolean tentative) {
        try {
            responseLock.lock();
            if (debug_)
                System.out.println("Sending response for requestID: " + requestID);



            if(role.equals(ReplicaRole.Aggregator) && latencyBenchmark.get()) {
                replyTime.put(requestID, System.nanoTime());
            }

            if (clientObservers.get(clientID) != null)
                clientObservers.get(clientID).onNext(serviceResponse);

            processedRequests.put(requestID, tentative ? ResponseStatus.TENTATIVE : ResponseStatus.DEFINITIVE);

            if (runThroughputBenchmark) {
                requestCounter.incrementAndGet();
                attackCounter.incrementAndGet();
            }

            if (!tentativeAggregation)
                responseBuilder.remove(requestID);

            resubmittedRequests.remove(requestID);
        } finally {
            responseLock.unlock();
        }
    }

    public ServerSideCART(int replicaID, ReplicaRole replicaRole, int numReplicas, int maxFaults,
                          boolean tentativeAggregation, int aggregationBufferSize, int numProxies,
                          boolean debug, boolean runThroughputBenchmark, boolean runLatencyBenchmark, int measurementInterval, int benchmarkDuration,
                          int numSignThreads, int numAggregateThreads, boolean byzantineAggregator,
                          double byzantineAttackRate, int attackStartingPoint, int attackDuration,
                          boolean batchSignatures) {

        this.hostInfo = loadHostInfo();
        this.blockList = ConcurrentHashMap.newKeySet();
        this.clientObservers = new HashMap<>();
        this.responseBuilder = new HashMap<>();
        this.processedRequests = new HashMap<>();
        this.resubmittedRequests = new HashSet<>();
        this.signatureShares = new HashMap<>();
        this.aggregationInProgress = new HashSet<>();
        this.signedRequests = new HashMap<>();
        this.receivedBatchLookup = new HashMap<>();
        this.currentBatchNumber = new AtomicLong(0x8000000000000000L);

        this.responseLock = new ReentrantLock(true);
        this.aggregationLock = new ReentrantLock(true);
        this.batchLock = new ReentrantLock(true);

        this.numReplicas = numReplicas;
        this.orderedThreshold = maxFaults + 1;
        this.unorderedThreshold = 2 * maxFaults + 1;
        this.replicaID = replicaID;
        this.role = replicaRole;
        this.tentativeAggregation = tentativeAggregation;
        this.debug_ = debug;

        this.batchSignatures = batchSignatures;

        this.firstRequest = new AtomicBoolean(false);
        this.requestCounter = new AtomicLong(0);
        this.attackCounter = new AtomicLong(0);
        this.measurementInterval = measurementInterval > 0 ? measurementInterval : 1000;
        this.benchmarkDuration = benchmarkDuration > 0 ? benchmarkDuration : 10000;
        this.runThroughputBenchmark = runThroughputBenchmark && (replicaRole == ReplicaRole.Aggregator);
        this.runLatencyBenchmark = runLatencyBenchmark && (replicaRole == ReplicaRole.Aggregator);

        this.byzantineAggregator = byzantineAggregator;
        this.byzantineAttackRate = byzantineAttackRate;
        this.attackStartingPoint = attackStartingPoint;
        this.attackDuration = attackDuration;

        this.latencyBenchmark = new AtomicBoolean(false);
        this.arrivalTime = new ConcurrentHashMap<>();
        this.readonlyTime = new ConcurrentHashMap<>();
        this.orderingTime = new ConcurrentHashMap<>();
        this.signatureTime = new ConcurrentHashMap<>();
        this.distributionTime = new ConcurrentHashMap<>();
        this.aggregationTime = new ConcurrentHashMap<>();
        this.replyTime = new ConcurrentHashMap<>();

        // Initialize the JNI binding
        System.loadLibrary("cart_jni_binding");
        JNIBinding.init_keys(numReplicas, new int[]{orderedThreshold, unorderedThreshold}, maxFaults, replicaID);

        try {
            ConsensusResponseHandler consensusResponseHandler = new ConsensusResponseHandler();
            new Thread(consensusResponseHandler).start();
            // Add a hook to shut the handler down if the program is terminated
            Runtime.getRuntime().addShutdownHook(new Thread(consensusResponseHandler::shutdown));

            this.aggregationHandler = new AggregationHandler(aggregationBufferSize, numAggregateThreads, new int[]{orderedThreshold, unorderedThreshold});
            new Thread(aggregationHandler).start();
            Runtime.getRuntime().addShutdownHook(new Thread(aggregationHandler::shutdown));

            this.signatureHandler = new SignatureHandler(numSignThreads);
            Runtime.getRuntime().addShutdownHook(new Thread(signatureHandler::shutdown));

            this.requestHandler = new RequestHandler();
            new Thread(requestHandler).start();
            Runtime.getRuntime().addShutdownHook(new Thread(requestHandler::shutdown));

            // Create and start the collector handler server
            collectorHandler = ServerBuilder.forPort(DEFAULT_COLLECTOR_PORT)
                    .addService(new SignatureCollectorService())
                    .build()
                    .start();

            // Add a hook to shut the server down if the program is terminated
            Runtime.getRuntime().addShutdownHook(new Thread(collectorHandler::shutdown));

            // Create and start the client handler server
            int DEFAULT_CLIENT_PORT = 52000;
            clientHandler = ServerBuilder.forPort(DEFAULT_CLIENT_PORT)
                    .addService(new ClientCommunicationService())
                    .addService(new DebugService())
                    .build()
                    .start();

            // Add a hook to shut the server down if the program is terminated
            Runtime.getRuntime().addShutdownHook(new Thread(clientHandler::shutdown));
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Server-Side CART has been launched with the following parameters:"
                + "\nreplicaID: " + replicaID
                + " with role: " + replicaRole
                + "\nOrdered Reply Quorum: " + orderedThreshold
                + "\nUnordered Reply Quorum: " + unorderedThreshold
                + "\nAggregation Strategy: " + (tentativeAggregation ? "tentative with Aggregation Buffer Size: "
                + aggregationBufferSize : "optimistic")
                + "\nDebug: " + debug
                + "\nServer-Side Throughput Benchmark: " + (runThroughputBenchmark ? "enabled with measurement interval "
                + measurementInterval + " and duration " + benchmarkDuration : "disabled")
                + "\nServer-Side Latency Benchmark: " + (runLatencyBenchmark ? "enabled" : "disabled")
                + "\nNum Sign Threads: " + numSignThreads
                + "\nNum Aggregate Threads: " + numAggregateThreads
                + "\nByzantine Aggregator: " + (byzantineAggregator ? "enabled with:" : "disabled")
                + "\nByzantine Attack Rate: " + byzantineAttackRate
                + "\nAttack Starting Point: " + attackStartingPoint
                + "\nAttack Duration: " + attackDuration
                + "\nBatch Signatures: " + (batchSignatures ? "enabled" : "disabled"));

        // Wait for the services to terminate
        try {
            clientHandler.awaitTermination();
            collectorHandler.awaitTermination();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private HashMap<Integer, String> loadHostInfo() {
        String separator = System.getProperty("file.separator");
        String filepath = "config" + separator + "CART-hosts.config";

        HashMap<Integer, String> hosts = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filepath))) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#")) {
                    String[] splitString = line.split(" ");
                    if (splitString.length == 2) {
                        hosts.put(Integer.parseInt(splitString[0]), splitString[1]);
                    } else {
                        System.err.println("Malformed CART config file");
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("CART config file not found");
        }
        return hosts;
    }

    public static void main(String[] args) {
        if (args.length >= 4) {
            // required parameters
            int replicaID = Integer.parseInt(args[0]);
            int numProxies = Integer.parseInt(args[1]);
            int numReplicas = Integer.parseInt(args[2]);
            int maxFaults = Integer.parseInt(args[3]);

            //optional parameters
            boolean tentativeAggregation = args.length < 5 || Boolean.parseBoolean(args[4]);
            int aggregationBufferSize = args.length >= 6 ? Integer.parseInt(args[5]) : 100;

            boolean debug = args.length >= 7 && Boolean.parseBoolean(args[6]);
            boolean runThroughputBenchmark = args.length >= 8 && Boolean.parseBoolean(args[7]);
            boolean runLatencyBenchmark = args.length >= 9 && Boolean.parseBoolean(args[8]);
            int measurementInterval = args.length >= 10 ? Integer.parseInt(args[9]) : 1000;
            int benchmarkDuration = args.length >= 11 ? Integer.parseInt(args[10]) : 10000;
            int numSignThreads = args.length >= 12 ? Integer.parseInt(args[11]) : 1;
            int numAggregateThreads = args.length >= 13 ? Integer.parseInt(args[12]) : 1;
            boolean byzantineAggregator = args.length >= 14 && Boolean.parseBoolean(args[13]);
            double byzantineAttackRate = args.length >= 15 ? Double.parseDouble(args[14]) : 0;
            int attackStartingPoint = args.length >= 16 ? Integer.parseInt(args[15]) : 0;
            int attackDuration = args.length >= 17 ? Integer.parseInt(args[16]) : 0;
            boolean batchSignatures = args.length >= 18 && Boolean.parseBoolean(args[17]);

            ReplicaRole replicaRole = replicaID == 0 ? ReplicaRole.Leader :
                    ((replicaID <= maxFaults + 1) ? ReplicaRole.Aggregator : ReplicaRole.Backup);
            new ServerSideCART(replicaID, replicaRole, numReplicas, maxFaults, tentativeAggregation,
                    aggregationBufferSize, numProxies, debug, runThroughputBenchmark, runLatencyBenchmark, measurementInterval, benchmarkDuration,
                    numSignThreads, numAggregateThreads, byzantineAggregator, byzantineAttackRate, attackStartingPoint,
                    attackDuration, batchSignatures);
        } else {
            System.out.println("usage: java ThreshSigServerHandler <replicaID> <numProxies> <numReplicas> <maxFaults> " +
                    "[tentativeAggregation?] [aggregationBufferSize] [debug] [runThroughputBenchmark?] [runLatencyBenchmark?] [measurementInterval] " +
                    "[benchmarkDuration] [numSignThreads] [numAggregateThreads] [byzantineAggregator?] [attackRate] " +
                    "[attackStartingPoint] [attackDuration] [batchSignatures]");
        }
    }
}
