package cart.adapter;

import bftsmart.tom.MessageContext;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;
import bftsmart.tom.server.defaultservices.DefaultSingleRecoverable;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class CombinedConsensusSocketAdapter {
    private final ByteBuffer socketBuffer;
    private final SocketChannel applicationSocketChannel;

    private final boolean debug_;

    public CombinedConsensusSocketAdapter(int replicaID, int reconnectDelay, boolean batchProcessing, boolean debug) {
        this.socketBuffer = ByteBuffer.allocate(1024 * 1024);
        this.debug_ = debug;

        this.applicationSocketChannel = connectToSocket("/tmp/cart-application.socket", reconnectDelay);

        // Start the actual service replica
        if (batchProcessing) {
            DefaultRecoverable batchExecutable = new BatchConsensusSocketAdapter();
            new ServiceReplica(replicaID, batchExecutable, batchExecutable);
        } else {
            DefaultSingleRecoverable singleExecutable = new SingleConsensusSocketAdapter();
            new ServiceReplica(replicaID, singleExecutable, singleExecutable);
        }
    }

    private synchronized byte[] executeUnorderedRequest(byte[] request) {
        if (debug_)
            System.out.println("ConsensusAdapter: Forwarding unordered request to the Application");

        socketBuffer.limit(3 * Integer.BYTES + request.length);

        int header = (2 * Integer.BYTES + request.length) & 0x7FFFFFFF;
        socketBuffer.putInt(header);
        socketBuffer.putInt(1);
        socketBuffer.putInt(request.length);
        socketBuffer.put(request);

        socketBuffer.position(0);
        while (socketBuffer.hasRemaining()) {
            try {
                applicationSocketChannel.write(socketBuffer);
            } catch (IOException e) {
                System.err.println("An error occurred while trying to submit command");
                throw new RuntimeException(e);
            }
        }

        socketBuffer.clear();

        // Read the header first
        socketBuffer.limit(Integer.BYTES);
        try {
            int bytesRead = applicationSocketChannel.read(socketBuffer);
            if (bytesRead < 0)
                throw new RuntimeException("Channel has been terminated");
        } catch (IOException e) {
            System.err.println("An error occurred while trying to submit command");
            throw new RuntimeException(e);
        }

        socketBuffer.flip();

        int numResponseBytes = socketBuffer.getInt();
        socketBuffer.clear();
        socketBuffer.limit(numResponseBytes);

        try {
            int bytesRead = 0;
            while (bytesRead < numResponseBytes) {
                bytesRead += applicationSocketChannel.read(socketBuffer);
                if (bytesRead < 0)
                    break;
            }
        } catch (IOException e) {
            System.err.println("SocketChannel has been closed");
        }

        if (debug_)
            System.out.println("ConsensusAdapter: Received response batch from the application");

        socketBuffer.flip();
        byte[] response = new byte[numResponseBytes];
        socketBuffer.get(response);
        socketBuffer.clear();

        return response;
    }

    private synchronized byte[][] executeRequestBatch(byte[][] requests) {
        if (debug_)
            System.out.println("ConsensusAdapter: Forwarding request batch to the Application");

        int numRequestBytes = 0;
        for (byte[] request : requests)
            numRequestBytes += request.length + Integer.BYTES;

        socketBuffer.limit(2*Integer.BYTES + numRequestBytes);

        int header = (1 << 31) | (Integer.BYTES + numRequestBytes);
        socketBuffer.putInt(header);
        socketBuffer.putInt(requests.length);

        for (byte[] request : requests) {
            socketBuffer.putInt(request.length);
            socketBuffer.put(request);
        }

        int previousPosition = socketBuffer.position();
        socketBuffer.position(0);
        while (socketBuffer.hasRemaining()) {
            try {
                applicationSocketChannel.write(socketBuffer);
            } catch (IOException e) {
                System.err.println("An error occurred while trying to submit command");
                throw new RuntimeException(e);
            }
        }

        socketBuffer.clear();
        socketBuffer.limit(Integer.BYTES);
        try {
            int bytesRead = applicationSocketChannel.read(socketBuffer);
            if (bytesRead < 0)
                throw new RuntimeException("Channel has been terminated");
        } catch (IOException e) {
            System.err.println("An error occurred while trying to submit command");
            throw new RuntimeException(e);
        }

        socketBuffer.flip();

        int numResponseBytes = socketBuffer.getInt();
        socketBuffer.clear();
        socketBuffer.limit(numResponseBytes);
        try {
            int bytesRead = 0;
            while (bytesRead < numResponseBytes) {
                bytesRead += applicationSocketChannel.read(socketBuffer);
                if (bytesRead < 0)
                    break;
            }
        } catch (IOException e) {
            System.err.println("SocketChannel has been closed");
        }

        // Extract the responses and return them to the consensus protocol
        socketBuffer.flip();
        byte[][] responses = new byte[requests.length][];
        for (int i = 0; i < requests.length; i++) {
            int responseLength = socketBuffer.getInt();
            responses[i] = new byte[responseLength];

            socketBuffer.get(responses[i]);
        }
        socketBuffer.clear();
        return responses;
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
                System.out.println("Socket: " + socketAddress + " refused connection");
                try {
                    Thread.sleep(reconnectDelay);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        return socketChannel;
    }

    private class BatchConsensusSocketAdapter extends DefaultRecoverable {
        @Override
        public void installSnapshot(byte[] state) {
        }

        @Override
        public byte[] getSnapshot() {
            return new byte[0];
        }

        @Override
        public byte[][] appExecuteBatch(byte[][] commands, MessageContext[] msgCtxs, boolean fromConsensus) {
            return executeRequestBatch(commands);
        }

        @Override
        public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
            return executeUnorderedRequest(command);
        }
    }

    private class SingleConsensusSocketAdapter extends DefaultSingleRecoverable {

        @Override
        public void installSnapshot(byte[] state) {
        }

        @Override
        public byte[] getSnapshot() {
            return new byte[0];
        }

        @Override
        public byte[] appExecuteOrdered(byte[] command, MessageContext msgCtx) {
            return executeRequestBatch(new byte[][]{command})[0];
        }

        @Override
        public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
            return executeUnorderedRequest(command);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Use: BatchConsensusSocketAdapter <replicaId> <batchProcessing?> [debug?]");
            System.exit(-1);
        }
        int replicaID = Integer.parseInt(args[0]);
        boolean batchProcessing = Boolean.parseBoolean(args[1]);
        boolean debug = args.length >= 3 && Boolean.parseBoolean(args[2]);
        new CombinedConsensusSocketAdapter(replicaID, 1000, batchProcessing, debug);
    }
}
