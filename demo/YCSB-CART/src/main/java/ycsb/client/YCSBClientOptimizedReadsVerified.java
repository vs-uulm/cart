package ycsb.client;

import com.yahoo.ycsb.ByteIterator;
import ycsb.datastructures.YCSBMessage;

import java.util.HashMap;
import java.util.Set;

public class YCSBClientOptimizedReadsVerified extends YCSBClientVerified {
    @Override
    public int read(String table, String key,
                    Set<String> fields, HashMap<String, ByteIterator> result) {
        HashMap<String, byte[]> results = new HashMap<>();
        YCSBMessage request = YCSBMessage.newReadRequest(table, key, fields, results);
        byte[] reply = clientHandler.invokeUnordered(request.getBytes());
        YCSBMessage replyMsg = YCSBMessage.getObject(reply);
        return verifyAndReturn(replyMsg, YCSBMessage.Type.READ);
    }
}
