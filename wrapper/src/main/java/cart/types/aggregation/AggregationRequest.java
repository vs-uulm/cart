package cart.types.aggregation;

import cart.types.signature.BLSSignature;
import cart.RequestType;
import com.google.protobuf.ByteString;

import java.util.HashSet;

public record AggregationRequest(long requestID, RequestType requestType, ByteString majorityResponse, HashSet<BLSSignature> signatureShares) {}
