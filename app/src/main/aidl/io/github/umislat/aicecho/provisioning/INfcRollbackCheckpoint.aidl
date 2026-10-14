package io.github.umislat.aicecho.provisioning;

// App-private persistence acknowledgement before the fixed NFC rollback is submitted.
interface INfcRollbackCheckpoint {
    void retain(String token, in byte[] observation, in byte[] replacement);
}
