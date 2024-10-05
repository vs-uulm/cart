package cart.binding;

import java.nio.ByteBuffer;

public class JNIBinding {
    public static native boolean init_keys(int num_signers, int[] thresholds, int max_faults, int signerIndex);

    public static native boolean init_relic();

    public static native byte[] getGroupPublicKey(int threshold);

    public static native boolean initPublicKeys(byte[][] publicKey, int[] thresholds);

    public static native byte[] sign(byte[] data, int threshold);

    public static native boolean verify(byte[] data, byte[] signature, int signerIndex, int threshold);

    public static native boolean verifyPublic(byte[] data, byte[] signature, int threshold);

    public static native byte[] aggregate(byte[][] signatures, int[] indices, byte[] data, int threshold, int verificationStrategy);

    public static native boolean aggregateVerify(byte[][] data, byte[][] signatures, int signerIndex, int threshold);
}
