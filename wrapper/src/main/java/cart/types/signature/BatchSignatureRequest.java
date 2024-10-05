package cart.types.signature;

import cart.RequestType;

public record BatchSignatureRequest(long batchID, long[] requestIDs, RequestType requestType, byte[][] requestData, byte[][] responseData) implements SignatureRequest {}
