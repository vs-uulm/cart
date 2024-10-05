package cart.setup;

import cart.setup.ConnectInfoResponse;
import cart.setup.IdManagementGrpc;
import cart.setup.IdRequestMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.*;
import java.util.Arrays;

public class IdClient {
    private final String publicIP;
    private final String privateIP;
    private final int bftClientPort;
    private final int bftReplicaPort;

    // negative port values indicate default values
    public IdClient(String publicIP, String privateIP, int bftClientPort, int bftReplicaPort) {
        this.publicIP = publicIP;
        this.privateIP = privateIP;
        this.bftClientPort = bftClientPort;
        this.bftReplicaPort = bftReplicaPort;
    }

    public void submitAndCollect(String idServer, String cartConfigFile, String consensusConfigFile) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(idServer + ":6666").usePlaintext().build();
        IdManagementGrpc.IdManagementBlockingStub stub = IdManagementGrpc.newBlockingStub(channel).withWaitForReady();

        IdRequestMessage request = IdRequestMessage.newBuilder().setHost(this.publicIP)
                .setBftClientPort(this.bftClientPort)
                .setBftReplicaPort(this.bftReplicaPort)
                .build();
        ConnectInfoResponse response = stub.submitConnectInfo(request);
        channel.shutdown();

        int numHosts = response.getHostsCount();
        HostInfo[] hostInfo = new HostInfo[numHosts];
        for(int i = 0; i < numHosts; i++) {
            hostInfo[i] = new HostInfo(response.getIds(i), response.getHosts(i), response.getBftClientPorts(i),
                    response.getBftReplicaPorts(i));
        }
        writeToFile(response.getId(), hostInfo, cartConfigFile, consensusConfigFile);
    }

    private void writeToFile(int id, HostInfo[] connectInfo, String cartConfigFile, String consensusConfigFile) {
        // Create the config for the server-side CART
        try (PrintWriter configWriter = new PrintWriter(cartConfigFile)) {
            Arrays.stream(connectInfo).sequential().forEach((hostInfo) -> {
                String configLine;
                if(hostInfo.ID() == id)
                    configLine = hostInfo.ID() + " " + this.privateIP;
                else
                    configLine = hostInfo.ID() + " " + hostInfo.host();
                configWriter.println(configLine);
            });
            configWriter.flush();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        // Create the config for the consensus library
        try (PrintWriter configWriter = new PrintWriter(consensusConfigFile)) {
            Arrays.stream(connectInfo).sequential().forEach((hostInfo) -> {
                String configLine;
                if(hostInfo.ID() == id)
                    configLine = hostInfo.ID() + " " + this.privateIP + " "
                        + hostInfo.bftClientPort() + " " + hostInfo.bftReplicaPort();
                else
                    configLine = hostInfo.ID() + " " + hostInfo.host() + " "
                            + hostInfo.bftClientPort() + " " + hostInfo.bftReplicaPort();
                configWriter.println(configLine);
            });
            configWriter.flush();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        // Write out the local replicaID
        try(PrintWriter idWriter = new PrintWriter("replicaID")) {
            idWriter.print(id);
            idWriter.flush();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        if(args.length == 7) {
            String idServer  = args[0];
            String publicIP = args[1];
            String privateIP = args[2];
            int bftClientPort = Integer.parseInt(args[3]);
            int bftReplicaPort = Integer.parseInt(args[4]);
            String cartConfigFile = args[5];
            String consensusConfigFile = args[6];
            IdClient client = new IdClient(publicIP, privateIP, bftClientPort, bftReplicaPort);
            client.submitAndCollect(idServer, cartConfigFile, consensusConfigFile);
        } else {
            System.out.println("Running in test mode");
            int numClients = 4;

            Thread[] handlers = new Thread[numClients];
            for (int i = 0; i < numClients; i++) {
                int finalI = i;
                handlers[i] = new Thread(() -> {
                    IdClient client = new IdClient("client" + finalI + "-public", "client" + finalI + "-private", 8080, 8081);
                    client.submitAndCollect("localhost", "cartTest.config", "consensusTest.config");
                }, "Client " + i);
                handlers[i].start();
            }

            for (int i = 0; i < numClients; i++) {
                try {
                    handlers[i].join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
