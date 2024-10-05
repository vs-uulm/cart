package cart.client;

import cart.*;
import cart.ClientCommunicationGrpc.ClientCommunicationStub;

import cart.binding.JNIBinding;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class ClientSideCART {
    private final HashMap<Integer, String> hostAddresses;

    private final HashMap<Integer, ManagedChannel> replicaChannels;
    private final HashMap<Integer, ClientCommunicationStub> replicaStubs;
    private final HashMap<Integer, ClientDebugGrpc.ClientDebugStub> debugStubs;
    private final HashMap<Integer, StreamObserver<ServiceRequest>> replicaStreamObservers;

    private final int clientID;

    private final AtomicLong requestId = new AtomicLong();

    private final HashMap<Long, PendingRequest> pendingRequests;
    private final ReentrantLock pendingLock;
    private final AtomicBoolean terminatedConnections;

    private record PendingRequest(byte[] request, CompletableFuture<byte[]> future) {
    }

    ;

    private record PendingResponse(ByteString response, int replicaID) {
    }

    ;

    private final boolean verify;

    private final int numReplicas;
    private final int orderedThreshold;
    private final int unorderedThreshold;

    private final VerificationHandler verificationHandler;

    public ClientSideCART(String hostConfigFile, boolean verify) {
        // generate a random client ID
        this(0, hostConfigFile, verify);
    }

    public ClientSideCART(int clientIdOffset, String hostConfigFile, boolean verify) {
        this.verify = verify;
        this.hostAddresses = loadHostInfo(hostConfigFile);
        this.pendingRequests = new HashMap<>();
        this.pendingLock = new ReentrantLock();

        this.replicaChannels = new HashMap<>();
        this.replicaStubs = new HashMap<>();
        this.debugStubs = new HashMap<>();
        this.replicaStreamObservers = new HashMap<>();

        Random random = new Random(System.nanoTime());
        this.clientID = (random.nextInt() + clientIdOffset) & 0x7FFFFFFF;

        this.terminatedConnections = new AtomicBoolean(false);

        requestId.set((long) this.clientID << 32);

        // Initialize channels and targets
        for (int id : hostAddresses.keySet()) {
            this.replicaChannels.put(id, ManagedChannelBuilder.forTarget(hostAddresses.get(id)).usePlaintext().build());
            this.replicaStubs.put(id, ClientCommunicationGrpc.newStub(replicaChannels.get(id)));
            this.debugStubs.put(id, ClientDebugGrpc.newStub(replicaChannels.get(id)));

            this.replicaStreamObservers.put(id, this.replicaStubs.get(id).serviceStream(new StreamObserver<ServiceResponse>() {
                @Override
                public void onNext(ServiceResponse response) {
                    processResponse(response, id);
                }

                @Override
                public void onError(Throwable throwable) {
                    throwable.printStackTrace();
                }

                @Override
                public void onCompleted() {
                    System.out.println("Client-Stream has been terminated");
                }
            }));
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::closeConnections));

        this.numReplicas = hostAddresses.size();
        int maxFaults = (this.numReplicas - 1) / 3;

        this.orderedThreshold = maxFaults + 1;
        this.unorderedThreshold = 2 * maxFaults + 1;

        if (verify) {
            System.loadLibrary("cart_jni_binding");
            this.verificationHandler = new VerificationHandler();

            // Retrieve group public keys from the service
            var blockingStub = ClientCommunicationGrpc.newBlockingStub(replicaChannels.get(0));
            PublicKeyResponse response = blockingStub.requestPublicKey(PublicKeyRequest.newBuilder().build());
            byte[][] publicKeys = new byte[response.getPublicKeysCount()][];
            int[] thresholds = new int[response.getThresholdsCount()];

            for(int i = 0; i < response.getPublicKeysCount(); i++) {
                publicKeys[i] = response.getPublicKeys(i).toByteArray();
                thresholds[i] = response.getThresholds(i);
            }
            JNIBinding.init_relic();
            JNIBinding.initPublicKeys(publicKeys, thresholds);
        } else {
            this.verificationHandler = null;
        }
    }

    public byte[] invokeOrdered(byte[] request) {
        long requestID = this.requestId.getAndIncrement();
        ServiceRequest orderedRequest = ServiceRequest.newBuilder()
                .setRequestType(RequestType.ORDERED_REQUEST)
                .setRequest(ByteString.copyFrom(request))
                .setId(requestID)
                .build();

        return invoke(orderedRequest);
    }

    public byte[] invokeUnordered(byte[] request) {
        long requestID = this.requestId.getAndIncrement();
        ServiceRequest unorderedRequest = ServiceRequest.newBuilder()
                .setRequestType(RequestType.UNORDERED_REQUEST)
                .setRequest(ByteString.copyFrom(request))
                .setId(requestID)
                .build();

        return invoke(unorderedRequest);
    }

    private byte[] invoke(ServiceRequest request) {
        if (terminatedConnections.get())
            return new byte[0];

        byte[] result = null;
        try {
            result = invokeStream(request).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            System.err.println("Could not retrieve result before timeout for requestID " + request.getId());
            submitDebugRequest(request.getId());
            result = new byte[0];

            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        return result;
    }

    private synchronized void processResponse(ServiceResponse response, int replicaID) {
        long requestID = response.getId();
        // TODO add mechanism to cope with invalid signatures
        try {
            pendingLock.lock();
            if (pendingRequests.containsKey(requestID) && !pendingRequests.get(requestID).future.isDone() && !terminatedConnections.get()) {
                if (!response.getResponse().isEmpty()) {
                    if (verify) {
                        verificationHandler.addVerificationRequest(response);
                    } else {
                        byte[] responseData = response.getResponse().toByteArray();
                        pendingRequests.get(requestID).future.complete(responseData);
                        pendingRequests.remove(requestID);
                    }
                } else {
                    //submitDebugRequest(requestID);
                    System.err.println("Response body for requestID: " + requestID + " is empty");
                    if (response.getSignature().isEmpty())
                        System.out.println("Signature is missing also");
                    else
                        System.out.println("Signature is present");
                }
            }
        } finally {
            pendingLock.unlock();
        }
    }

    CompletableFuture<byte[]> invokeStream(ServiceRequest request) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        try {
            pendingLock.lock();
            pendingRequests.put(request.getId(), new PendingRequest(request.getRequest().toByteArray(), future));
        } finally {
            pendingLock.unlock();
        }
        for (int id : hostAddresses.keySet())
            this.replicaStreamObservers.get(id).onNext(request);

        return future;
    }

    public void closeConnections() {
        terminatedConnections.set(true);
        // Wait for further invocations to notice the change
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Closing connection");
        // Close replica connections
        for (int id : hostAddresses.keySet()) {
            this.replicaStreamObservers.get(id).onCompleted();
            this.replicaChannels.get(id).shutdown();
        }
    }

    public synchronized void submitDebugRequest(long requestID) {
        ClientDebugRequest debugRequest = ClientDebugRequest.newBuilder().setRequestID(requestID).build();
        for (int id : hostAddresses.keySet()) {
            this.debugStubs.get(id).clientDebug(debugRequest, new StreamObserver<ClientDebugResponse>() {
                @Override
                public void onNext(ClientDebugResponse response) {
                    System.out.println("Received DebugResponse from replica " + response.getReplicaID() +
                            "\n for requestID: " + response.getRequestID() +
                            "\n RequestBuilder: " + response.getBuilderIsPresent() +
                            "\n Response: " + response.getResponseIsPresent() +
                            "\n Signature: " + response.getSignatureIsPresent() +
                            "\n Processed: " + response.getProcessedRequests() +
                            "\n Responded: " + response.getResponded() +
                            "\n RequestType " + response.getRequestType() +
                            "\n AggregatedSignature " + response.getAggregatedSignature() +
                            "\n Signature Shares Present: " + response.getSignatureSharesPresent());
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            });
        }
    }

    private HashMap<Integer, String> loadHostInfo(String configFile) {
        String separator = System.getProperty("file.separator");
        String filepath = "config" + separator + configFile;
        HashMap<Integer, String> hosts = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filepath))) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#")) {
                    String[] splitString = line.split(" ");
                    if (splitString.length == 3) {
                        int id = Integer.parseInt(splitString[0]);
                        String host = splitString[1] + ":" + splitString[2];
                        hosts.put(id, host);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Config file not found: " + configFile);
        }
        return hosts;
    }

    public int getClientID() {
        return this.clientID;
    }

    private class VerificationHandler {
        private final LinkedBlockingQueue<ServiceResponse> verificationRequests;
        private final HashSet<Long> processedRequests;

        private final ExecutorService executorService;

        public VerificationHandler() {
            this.verificationRequests = new LinkedBlockingQueue<>();
            this.processedRequests = new HashSet<>();

            this.executorService = Executors.newFixedThreadPool(1);
            executorService.execute(() -> {
                JNIBinding.init_relic();
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    while (true) {
                        ServiceResponse response = verificationRequests.take();
                        long requestID = response.getId();

                        if (processedRequests.contains(requestID))
                            continue;

                        byte[] hashedData = null;
                        byte[] responseData = null;
                        try {
                            pendingLock.lock();
                            responseData = response.getResponse().toByteArray();
                            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                            buffer.putLong(requestID);
                            digest.update(buffer.array());
                            digest.update(pendingRequests.get(requestID).request);
                            digest.update(responseData);
                            hashedData = digest.digest();
                        } finally {
                            pendingLock.unlock();
                        }

                        if(response.getResponseType().equals(ResponseType.SINGLE_RESPONSE)) {
                            if (JNIBinding.verifyPublic(hashedData, response.getSignature().toByteArray(),
                                    response.getRequestType().equals(RequestType.ORDERED_REQUEST) ? orderedThreshold : unorderedThreshold)) {
                                try {
                                    pendingLock.lock();
                                    processedRequests.add(requestID);
                                    pendingRequests.get(requestID).future.complete(responseData);
                                    pendingRequests.remove(requestID);
                                } finally {
                                    pendingLock.unlock();
                                }
                            } else {
                                System.err.println("Error: Signature for request " + requestID + " is invalid");
                                submitDebugRequest(requestID);
                            }
                        } else if(response.getResponseType().equals(ResponseType.BATCH_RESPONSE)) {
                            // Verify that the hash is present in the list
                            if(!response.getAuxiliaryData(response.getAuxiliaryIndex()).equals(ByteString.copyFrom(hashedData))) {
                                System.err.println("Error: hash value is not matching for the specified index");
                                continue;
                            }

                            for(ByteString hash : response.getAuxiliaryDataList())
                                digest.update(hash.toByteArray());

                            byte[] hashedBatchData = digest.digest();
                            ByteString hashedDataBytes = ByteString.copyFrom(hashedBatchData);

                            if (JNIBinding.verifyPublic(hashedBatchData, response.getSignature().toByteArray(),
                                    response.getRequestType().equals(RequestType.ORDERED_REQUEST) ? orderedThreshold : unorderedThreshold)) {
                                try {
                                    pendingLock.lock();
                                    processedRequests.add(requestID);
                                    pendingRequests.get(requestID).future.complete(responseData);
                                    pendingRequests.remove(requestID);
                                } finally {
                                    pendingLock.unlock();
                                }
                            } else {
                                System.err.println("Error: Batch Signature for request " + requestID + " is invalid");
                                System.out.println("Final hash data: " + hashedDataBytes);
                                System.out.println("Signature: " + response.getSignature());
                            }
                        } else {
                            System.out.println("Unknown Response Type");
                        }
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        public void addVerificationRequest(ServiceResponse response) {
            verificationRequests.add(response);
        }
    }
}
