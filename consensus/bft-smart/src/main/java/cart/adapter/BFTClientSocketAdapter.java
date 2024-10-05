package cart.adapter;

import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

public class BFTClientSocketAdapter {
    private final int numProxies;
    private final AsynchServiceProxy[] serviceProxies;
    private final AtomicInteger proxyCounter;
    private final boolean debug_;

    public BFTClientSocketAdapter(int replicaID, int numReplicas, int numProxies, boolean debug) {
        this(replicaID, numReplicas, numProxies, "/tmp/cart-client.socket", debug);
    }

    public BFTClientSocketAdapter(int replicaID, int numReplicas, int numProxies, String socketAddress, boolean debug) {
        this.numProxies = numProxies;
        this.proxyCounter = new AtomicInteger(0);
        this.serviceProxies = new AsynchServiceProxy[numProxies];
        this.debug_ = debug;


        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            Files.deleteIfExists(Path.of(socketAddress));
            serverSocketChannel.bind(UnixDomainSocketAddress.of(socketAddress));
            SocketChannel socketChannel = serverSocketChannel.accept();

            for (int i = 0; i < numProxies; i++) {
                this.serviceProxies[i] = new AsynchServiceProxy(1000 + (numReplicas * i) + replicaID);
            }
            
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            while (true) {
                buffer.limit(Integer.BYTES);
                try {
                    int bytesRead = socketChannel.read(buffer);
                    if (bytesRead < 0)
                        break;

                } catch (IOException e) {
                    System.err.println("An error occurred while trying to read the request header");
                    e.printStackTrace();
                }

                buffer.flip();
                int headerValue = buffer.getInt();
                buffer.clear();
                boolean ordered = headerValue >= 0;

                int requestLength = 0x7FFFFFFF & headerValue;
                // Allocate extra space for the CART ID
                buffer.limit(requestLength + Long.BYTES);
                try {
                    int bytesRead = socketChannel.read(buffer);
                    if (bytesRead < 0)
                        break;

                } catch (IOException e) {
                    System.err.println("An error occurred while trying to read the request payload");
                    e.printStackTrace();
                }

                byte[] request = new byte[requestLength + Long.BYTES];
                buffer.flip();
                buffer.get(request);
                buffer.clear();

                submitTOMRequest(request, ordered, -1);
            }
            System.err.println("SocketChannel has been terminated");

            System.out.println("Closing CART BFT client socket");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.out.println("Closing channel -> caught in the finally block");
        }
    }

    private synchronized void submitTOMRequest(byte[] request, boolean ordered, int proxyID) {
        int index = (proxyID < 0) ? proxyCounter.getAndIncrement() % this.numProxies : proxyID;

        if (debug_) {
            ByteBuffer requestIDBytes = ByteBuffer.wrap(request, 0, 8);
            System.out.println("BFTClientAdapter: submitting TOM request with requestID: " + requestIDBytes.getLong());
        }

        int operationID = serviceProxies[index].invokeAsynchRequest(request, new ReplyListener() {
            private int numReplies = 0;
            private int quorum = 2 * serviceProxies[index].getViewManager().getCurrentViewF() + 1;
            private final TOMMessage[] replies = new TOMMessage[serviceProxies[index].getViewManager().getCurrentViewN()];

            // TODO improve this
            private final Comparator<byte[]> comparator = (o1, o2) -> Arrays.equals(o1, o2) ? 0 : -1;

            @Override
            public void reset() {
                System.out.println("Performing a request reset");
                // Recompute the quorum here
                quorum = 2 * serviceProxies[index].getViewManager().getCurrentViewF() + 1;

                // reset the replies
                numReplies = 0;
                Arrays.fill(replies, null);
            }

            @Override
            public void replyReceived(RequestContext context, TOMMessage reply) {
                int matchingReplies = 1;
                int pos = reply.getSender();

                if (replies[pos] == null)
                    numReplies++;
                replies[pos] = reply;

                for (int i = 0; i < replies.length; i++) {
                    if (i != pos && replies[i] != null && (comparator.compare(replies[i].getContent(), reply.getContent()) == 0)) {
                        if (++matchingReplies >= quorum) {
                            serviceProxies[index].cleanAsynchRequest(context.getOperationId());
                            return;
                        }
                    }
                }

                if (numReplies == serviceProxies[index].getViewManager().getCurrentViewN()) {
                    serviceProxies[index].cleanAsynchRequest(context.getOperationId());
                    if (context.getRequestType().equals(TOMMessageType.ORDERED_REQUEST)) {
                        System.err.println("System misbehaviour detected: Unable to find a majority with ordered request");
                        // TODO issue a view change request here
                        return;
                    } else {
                        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                        buffer.put(request, 0, Long.BYTES);
                        long requestID = buffer.getLong(0);
                        System.out.println("Re-submitting unordered request with only " + matchingReplies + " matching replies with requestID " + requestID);
                        submitTOMRequest(request, true, index);
                    }
                }
            }
        }, ordered ? TOMMessageType.ORDERED_REQUEST : TOMMessageType.UNORDERED_REQUEST);
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Use: ClientSocketAdapter <replicaID> <numReplicas> <numProxies> [debug]");
            System.exit(-1);
        }

        int replicaID = Integer.parseInt(args[0]);
        int numProxies = Integer.parseInt(args[1]);
        int numReplicas = Integer.parseInt(args[2]);
        boolean debug = args.length >= 4 && Boolean.parseBoolean(args[3]);

        new BFTClientSocketAdapter(replicaID, numReplicas, numProxies, debug);
    }
}




















