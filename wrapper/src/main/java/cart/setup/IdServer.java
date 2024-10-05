package cart.setup;

import cart.setup.ConnectInfoResponse;
import cart.setup.IdManagementGrpc;
import cart.setup.IdRequestMessage;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IdServer {
    private static class IdManagementService extends IdManagementGrpc.IdManagementImplBase {
        private final AtomicInteger highestID;
        private final int numReplicas;

        private final ConcurrentHashMap<Integer, HostInfo> connectionInformation;
        private final ConcurrentHashMap<Integer, StreamObserver<ConnectInfoResponse>> responseObservers;

        public IdManagementService(int numReplicas) {
            this.numReplicas = numReplicas;
            this.highestID = new AtomicInteger(0);

            this.connectionInformation = new ConcurrentHashMap<>();
            this.responseObservers = new ConcurrentHashMap<>();
        }

        @Override
        public void submitConnectInfo(IdRequestMessage request, StreamObserver<ConnectInfoResponse> responseObserver) {
            int replicaID = highestID.getAndIncrement();
            connectionInformation.put(replicaID, new HostInfo(replicaID, request.getHost(), request.getBftClientPort(),
                    request.getBftReplicaPort()));
            responseObservers.put(replicaID, responseObserver);

            if(replicaID + 1 == numReplicas) {
                ConnectInfoResponse.Builder baseResponse = ConnectInfoResponse.newBuilder();
                connectionInformation.forEach((id, hostInfo) -> {
                    baseResponse.addIds(id);
                    baseResponse.addHosts(hostInfo.host());
                    baseResponse.addBftClientPorts(hostInfo.bftClientPort());
                    baseResponse.addBftReplicaPorts(hostInfo.bftReplicaPort());
                });

                responseObservers.forEach((id, observer) -> {
                    ConnectInfoResponse response = baseResponse.setId(id).build();
                    observer.onNext(response);
                    observer.onCompleted();
                });

                responseObservers.clear();

                // Wait for the RPCs to be received
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                System.exit(0);
            }
        }
    }

    public static void main(String[] args) {
        int numReplicas = args.length == 1 ? Integer.parseInt(args[0]) : 4;
        try {
            Server IdServer = ServerBuilder.forPort(6666)
                    .addService(new IdManagementService(numReplicas))
                    .build()
                    .start();

            Runtime.getRuntime().addShutdownHook(new Thread(IdServer::shutdown));

            IdServer.awaitTermination();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
