package cart.adapter;

import cart.server.ServerSideCART;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

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
import java.util.concurrent.locks.ReentrantLock;

public abstract class ApplicationSocketAdapter {
    private final String applicationSocketAddress;
    private final String cartSocketAddress;
    private SocketChannel consensusSocketChannel;
    private SocketChannel cartSocketChannel;
    private final boolean debug_;
    private final ByteBuffer socketBuffer;

    private Server readOnlyHandler;
    private final ReentrantLock executeLock;
    private final ReentrantLock cartResponseLock;

    public ApplicationSocketAdapter(boolean debug) {
        this(debug, "/tmp/cart-application.socket", "/tmp/cart-response-collector.socket");
    }

    public ApplicationSocketAdapter(boolean debug, String applicationSocketAddress, String cartSocketAddress) {
        this.debug_ = debug;
        this.applicationSocketAddress = applicationSocketAddress;
        this.cartSocketAddress = cartSocketAddress;
        this.socketBuffer = ByteBuffer.allocate(1024 * 1024);

        this.executeLock = new ReentrantLock();
        this.cartResponseLock = new ReentrantLock();

        int DEFAULT_READONLY_PORT = 52015;
        try {
            readOnlyHandler = ServerBuilder.forPort(DEFAULT_READONLY_PORT)
                    .addService(new ReadOnlyService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(readOnlyHandler::shutdown));
    }

    public void listen() {
        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            Files.deleteIfExists(Path.of(this.applicationSocketAddress));
            serverSocketChannel.bind(UnixDomainSocketAddress.of(this.applicationSocketAddress));

            consensusSocketChannel = serverSocketChannel.accept();

            // Connect to the Server-Side CART
            while (true) {
                try {
                    cartSocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
                    cartSocketChannel.configureBlocking(true);
                    cartSocketChannel.connect(UnixDomainSocketAddress.of(cartSocketAddress));
                    break;
                } catch (IOException e) {
                    try {
                        System.out.println("ApplicationSocketAdapter: trying to connect to the Server-Side CART");
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            while (true) {
                processCommands();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private SocketChannel connectToSocket(String socketAddress, int reconnectDelay) {
        UnixDomainSocketAddress applicationSocketAddress = UnixDomainSocketAddress.of(socketAddress);

        SocketChannel socketChannel;
        while (true) {
            try {
                socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
                socketChannel.configureBlocking(true);
                socketChannel.connect(applicationSocketAddress);
                break;
            } catch (IOException e) {
                try {
                    Thread.sleep(reconnectDelay);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        return socketChannel;
    }

    private void processCommands() {
        try {
            socketBuffer.limit(Integer.BYTES);
            int bytesRead = consensusSocketChannel.read(socketBuffer);
            if (bytesRead < 0)
                return;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        socketBuffer.flip();
        int headerValue = socketBuffer.getInt();
        boolean ordered = headerValue < 0;
        int requestPayloadLength = headerValue & 0x7FFFFFFF;

        socketBuffer.limit(requestPayloadLength + Integer.BYTES);
        try {
            int bytesRead = 0;
            while (bytesRead < requestPayloadLength) {
                bytesRead += consensusSocketChannel.read(socketBuffer);
                if (bytesRead < 0)
                    break;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        socketBuffer.position(Integer.BYTES);

        if(debug_)
            System.out.println("ApplicationAdapter: Received " + (ordered ? "ordered " : "unordered ") + "request.");

        int responsePayloadLength = 0;
        if (!ordered) {
            int numRequests = socketBuffer.getInt(); // this is actually not needed
            int requestLength = socketBuffer.getInt();
            byte[] request = new byte[requestLength - Long.BYTES];
            // Trim the request length and CART ID here
            long requestID = socketBuffer.getLong();
            socketBuffer.get(request);

            byte[] result;
            try {
                executeLock.lock();
                result = executeUnordered(request);
            } finally {
                executeLock.unlock();
            }
            responsePayloadLength = result.length + Integer.BYTES;
            socketBuffer.limit(Integer.BYTES + requestPayloadLength + responsePayloadLength);
            // Set new total payload length
            socketBuffer.putInt(0, (requestPayloadLength + responsePayloadLength) & 0x7FFFFFFF);
            socketBuffer.putInt(result.length);
            socketBuffer.put(result);
        } else {
            int numRequests = socketBuffer.getInt();
            byte[][] responses = new byte[numRequests][];
            byte[][] requests = new byte[numRequests][];

            try {
                executeLock.lock();
                for (int i = 0; i < numRequests; i++) {
                    int requestLength = socketBuffer.getInt();
                    long requestID = socketBuffer.getLong();

                    requests[i] = new byte[requestLength - Long.BYTES];
                    socketBuffer.get(requests[i]);

                    responses[i] = executeOrdered(requests[i]);
                    responsePayloadLength += responses[i].length + Integer.BYTES;
                }
            } finally {
                executeLock.unlock();
            }

            socketBuffer.limit(Integer.BYTES + requestPayloadLength + responsePayloadLength);
            // Set new total payload length
            socketBuffer.putInt(0, (requestPayloadLength + responsePayloadLength) | 0x80000000);

            for (byte[] response : responses) {
                socketBuffer.putInt(response.length);
                socketBuffer.put(response);
            }
        }


        // Now forward the response to the server-side CART using the following final format
        /*
            31                                                              0
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |1|                   PayloadLength (31 Bit)                    |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                      Batch Size (32 Bit)                      |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                   RequestLength #1 (32 Bit)                   |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                      CART ID #1 (64 Bit)                      |
            |                                                               |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                                                               |
            |                           Request #1                          |
            |                              ...                              |
            |                                                               |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                              ...                              |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                   ResponseLength #1 (32 Bit)                  |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                                                               |
            |                          Response #1                          |
            |                              ...                              |
            |                                                               |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
            |                              ...                              |
            |- - - - - - - -|- - - - - - - -|- - - - - - - -|- - - - - - - -|
        */

        socketBuffer.position(0);
        try {
            cartResponseLock.lock();
            while (socketBuffer.hasRemaining()) {
                try {
                    cartSocketChannel.write(socketBuffer);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            cartResponseLock.unlock();
        }

        socketBuffer.putInt(requestPayloadLength, responsePayloadLength);
        socketBuffer.position(requestPayloadLength);

        while (socketBuffer.hasRemaining()) {
            try {
                consensusSocketChannel.write(socketBuffer);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        socketBuffer.clear();
    }

    protected abstract byte[] executeUnordered(byte[] command);

    protected abstract byte[] executeOrdered(byte[] command);

    private class ReadOnlyService extends ReadOnlyGrpc.ReadOnlyImplBase {
        private final ByteBuffer socketBuffer;
        private final MessageDigest digest;
        private final ReentrantLock digestLock;

        private final ReadOnlyResultHashes.Builder responseBuilder;

        public ReadOnlyService() {
            this.socketBuffer = ByteBuffer.allocate(1024 * 1024);
            this.responseBuilder = ReadOnlyResultHashes.newBuilder();
            this.digestLock = new ReentrantLock();
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public StreamObserver<ReadOnlyRequestBatch> submitReadOnlyRequests(StreamObserver<ReadOnlyResultHashes> responseObserver) {
            return new StreamObserver<ReadOnlyRequestBatch>() {
                @Override
                public void onNext(ReadOnlyRequestBatch readOnlyRequestBatch) {
                    int numRequests = readOnlyRequestBatch.getRequestsCount();
                    byte[][] requests = new byte[numRequests][];
                    byte[][] results = new byte[numRequests][];

                    int payLoadLength = Integer.BYTES;
                    try {
                        digestLock.lock();
                        try {
                            executeLock.lock();
                            for (int i = 0; i < numRequests; i++) {
                                requests[i] = readOnlyRequestBatch.getRequests(i).toByteArray();
                                results[i] = executeUnordered(requests[i]);
                                payLoadLength += requests[i].length + results[i].length + Long.BYTES + 2 * Integer.BYTES;

                                responseBuilder.addId(readOnlyRequestBatch.getId(i))
                                        .addHashes(ByteString.copyFrom(digest.digest(results[i])));
                            }
                        } finally {
                            executeLock.unlock();
                        }
                    } finally {
                        digestLock.unlock();
                    }

                    socketBuffer.limit(2 * Integer.BYTES + payLoadLength);
                    socketBuffer.putInt(payLoadLength);
                    socketBuffer.putInt(numRequests);

                    for(int i = 0; i < numRequests; i++) {
                        socketBuffer.putInt(requests[i].length + Long.BYTES);
                        socketBuffer.putLong(readOnlyRequestBatch.getId(i));
                        socketBuffer.put(requests[i]);
                    }

                    for(int i = 0; i < numRequests; i++) {
                        socketBuffer.putInt(results[i].length);
                        socketBuffer.put(results[i]);
                    }

                    socketBuffer.flip();
                    try {
                        cartResponseLock.lock();
                        while (socketBuffer.hasRemaining()) {
                            try {
                                cartSocketChannel.write(socketBuffer);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    } finally {
                        cartResponseLock.unlock();
                    }
                    socketBuffer.clear();

                    responseObserver.onNext(responseBuilder.build());
                    responseBuilder.clear();
                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onCompleted() {

                }
            };
        }
    }
}
