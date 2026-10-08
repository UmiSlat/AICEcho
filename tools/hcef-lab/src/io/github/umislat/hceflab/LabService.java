package io.github.umislat.hceflab;

import android.nfc.cardemulation.HostNfcFService;
import android.os.Bundle;
import android.util.Log;

public final class LabService extends HostNfcFService {
    @Override public byte[] processNfcFPacket(byte[] request, Bundle extras) {
        boolean provisioned = getSharedPreferences("lab", MODE_PRIVATE).getBoolean("provisioned", false);
        int sc = getSharedPreferences("lab", MODE_PRIVATE).getInt("image_sc", 0x4000);
        byte[] response = provisioned ? LabProtocol.replyProvisioned(request) : LabProtocol.reply(request, sc);
        Log.i("HcefLab", "RX=" + LabProtocol.hex(request) + " TX=" + LabProtocol.hex(response));
        return response;
    }

    @Override public void onDeactivated(int reason) {
        Log.i("HcefLab", "deactivated=" + reason);
    }
}
