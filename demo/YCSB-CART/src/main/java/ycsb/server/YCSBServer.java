package ycsb.server;

import cart.adapter.ApplicationSocketAdapter;
import ycsb.datastructures.YCSBMessage;
import ycsb.datastructures.YCSBTable;

import java.util.Random;
import java.util.TreeMap;

public class YCSBServer extends ApplicationSocketAdapter {
    private final TreeMap<String, YCSBTable> mTables;

    private final boolean _debug;
    private final boolean faultyReplica;
    private final Random random;

    private YCSBServer(boolean debug, boolean faultyReplica) {
        super(debug);
        this.mTables = new TreeMap<>();
        this._debug = debug;
        this.faultyReplica = faultyReplica;
        this.random = new Random(System.currentTimeMillis());
        listen();
    }

    @Override
    protected byte[] executeUnordered(byte[] command) {
        YCSBMessage aRequest = YCSBMessage.getObject(command);
        YCSBMessage reply = YCSBMessage.newErrorMessage("");
        if (aRequest == null) {
            System.err.println("Could not parse YCSBMessage");
            return reply.getBytes();
        }

        if(faultyReplica) {
            byte[] randomErrorMessage = new byte[64];
            random.nextBytes(randomErrorMessage);
            reply = YCSBMessage.newErrorMessage(new String(randomErrorMessage));
            return reply.getBytes();
        }

        if (_debug)
            System.out.println("Unordered " + aRequest.getType() + " accessing table: "
                                            + aRequest.getTable() + " and record: " + aRequest.getKey());

        switch (aRequest.getType()) {
            case READ: { // ##### operation: read #####
                switch (aRequest.getEntity()) {
                    case RECORD: // ##### entity: record #####
                        if (!mTables.containsKey(aRequest.getTable())) {
                            reply = YCSBMessage.newErrorMessage("Table not found: " + aRequest.getTable());
                            break;
                        }
                        if (!mTables.get(aRequest.getTable()).containsKey(aRequest.getKey())) {
                            reply = YCSBMessage.newErrorMessage("Record not found" + aRequest.getKey());
                            break;
                        } else {
                            reply = YCSBMessage.newReadResponse(mTables.get(aRequest.getTable()).get(aRequest.getKey()), 0);
                            break;
                        }
                }
                break;
            }
            default:
                System.err.println("Received unknown unordered message type " + aRequest.getType());
        }

        return reply.getBytes();
    }

    @Override
    protected byte[] executeOrdered(byte[] command) {
        YCSBMessage aRequest = YCSBMessage.getObject(command);
        YCSBMessage reply = YCSBMessage.newErrorMessage("");
        if (aRequest == null) {
            System.err.println("Could not parse YCSBMessage");
            return reply.getBytes();
        }

        if (_debug)
            System.out.println("Ordered " + aRequest.getType() + " accessing table: "
                    + aRequest.getTable() + " and record: " + aRequest.getKey());

        switch (aRequest.getType()) {
            case CREATE: { // ##### operation: create #####
                switch (aRequest.getEntity()) {
                    case RECORD: // ##### entity: record #####
                        System.out.println("Creating record");
                        if (!mTables.containsKey(aRequest.getTable())) {
                            mTables.put((String) aRequest.getTable(), new YCSBTable());
                        }
                        if (!mTables.get(aRequest.getTable()).containsKey(aRequest.getKey())) {
                            mTables.get(aRequest.getTable()).put(aRequest.getKey(), aRequest.getValues());
                            reply = YCSBMessage.newInsertResponse(0);
                        }
                        break;
                    default: // Only create records
                        break;
                }
                break;
            }

            case UPDATE: { // ##### operation: update #####
                switch (aRequest.getEntity()) {
                    case RECORD: // ##### entity: record #####
                        if (!mTables.containsKey(aRequest.getTable())) {
                            mTables.put((String) aRequest.getTable(), new YCSBTable());
                        }
                        mTables.get(aRequest.getTable()).put(aRequest.getKey(), aRequest.getValues());
                        reply = YCSBMessage.newUpdateResponse(1);
                        break;
                    default: // Only update records
                        break;
                }
                break;
            }

            case READ: { // ##### operation: read #####
                switch (aRequest.getEntity()) {
                    case RECORD: // ##### entity: record #####
                        if (!mTables.containsKey(aRequest.getTable())) {
                            reply = YCSBMessage.newErrorMessage("Table not found");
                            break;
                        }
                        if (!mTables.get(aRequest.getTable()).containsKey(aRequest.getKey())) {
                            reply = YCSBMessage.newErrorMessage("Record not found");
                            break;
                        } else {
                            reply = YCSBMessage.newReadResponse(mTables.get(aRequest.getTable()).get(aRequest.getKey()), 0);
                            break;
                        }
                }
                break;
            }
            default:
                System.err.println("Received unknown ordered message type " + aRequest.getType());
        }
        return reply.getBytes();
    }

    public static void main(String[] args) {
        boolean debug = args.length >= 1 && Boolean.parseBoolean(args[0]);
        boolean faultyReplica = args.length >= 2 && Boolean.parseBoolean(args[1]);
        new YCSBServer(debug, faultyReplica);
    }
}
