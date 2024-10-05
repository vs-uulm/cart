package cart.types.signature;

import cart.RequestType;
import com.google.protobuf.ByteString;

public record BLSSignature(long requestID, ByteString signedData, ByteString signature, RequestType requestType, int signerIndex) {}
