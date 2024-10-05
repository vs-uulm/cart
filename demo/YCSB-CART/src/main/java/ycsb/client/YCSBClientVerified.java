package ycsb.client;

import cart.client.ClientSideCART;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import ycsb.datastructures.YCSBMessage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

public class YCSBClientVerified extends DB {

    protected final static AtomicInteger idCounter = new AtomicInteger(1);
    protected ClientSideCART clientHandler;

    @Override
    public void init() {
        int clientId = idCounter.getAndIncrement();
        clientHandler = new ClientSideCART(clientId, "cart_ycsb_hosts.config", true);
    }

    @Override
    public void cleanup() {
        clientHandler.closeConnections();
    }

    @Override
    public int delete(String arg0, String arg1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int insert(String table, String key,
                      HashMap<String, ByteIterator> values) {

        Iterator<String> keys = values.keySet().iterator();
        HashMap<String, byte[]> map = new HashMap<>();
        while (keys.hasNext()) {
            String field = keys.next();
            map.put(field, values.get(field).toArray());
        }
        YCSBMessage msg = YCSBMessage.newInsertRequest(table, key, map);
        byte[] reply = clientHandler.invokeOrdered(msg.getBytes());
        if (reply.length == 0) {
            System.err.println("Received empty response");
            return -1;
        }

        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return verifyAndReturn(replyMsg, YCSBMessage.Type.CREATE);
    }

    @Override
    public int read(String table, String key,
                    Set<String> fields, HashMap<String, ByteIterator> result) {
        HashMap<String, byte[]> results = new HashMap<>();
        YCSBMessage request = YCSBMessage.newReadRequest(table, key, fields, results);
        byte[] reply = clientHandler.invokeOrdered(request.getBytes());
        if (reply.length == 0) {
            System.err.println("Received empty response");
            return -1;
        }

        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return verifyAndReturn(replyMsg, YCSBMessage.Type.READ);
    }

    @Override
    public int scan(String arg0, String arg1, int arg2, Set<String> arg3,
                    Vector<HashMap<String, ByteIterator>> arg4) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(String table, String key,
                      HashMap<String, ByteIterator> values) {
        Iterator<String> keys = values.keySet().iterator();
        HashMap<String, byte[]> map = new HashMap<>();
        while (keys.hasNext()) {
            String field = keys.next();
            map.put(field, values.get(field).toArray());
        }
        YCSBMessage msg = YCSBMessage.newUpdateRequest(table, key, map);
        byte[] reply = clientHandler.invokeOrdered(msg.getBytes());
        if (reply.length == 0) {
            System.err.println("Received empty response");
            return -1;
        }

        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return verifyAndReturn(replyMsg, YCSBMessage.Type.UPDATE);
    }

    protected int verifyAndReturn(YCSBMessage replyMsg, YCSBMessage.Type requestType) {
        if(replyMsg == null) {
            System.err.println("Error: could not parse YCSB message");
            return -1;
        } else if (replyMsg.getResult() == -1 && !requestType.equals(YCSBMessage.Type.READ)) {
            System.err.println("Received YCSB error message");
        }
        return replyMsg.getResult();
    }
}
