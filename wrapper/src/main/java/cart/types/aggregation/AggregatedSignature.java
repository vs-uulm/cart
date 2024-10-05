package cart.types.aggregation;

import cart.RequestType;
import com.google.protobuf.ByteString;

public record AggregatedSignature(long requestID, RequestType requestType, ByteString signature, ByteString signedData, int[] shareIndices) {
}
