package io.github.umislat.aimesimulator.provisioning;

import android.os.Bundle;

// Provisioning only targets this app's AimeHostService; no generic command or path.
interface IHceShellService {
    Bundle inspect() = 0;
    Bundle startRestoreProbe(String token) = 1;
    Bundle probeStatus() = 2;
    Bundle verifyPreviousProbe(String token) = 3;
    Bundle startNfcSnapshot(String token) = 4;
    Bundle snapshotStatus() = 5;
    Bundle readNfcSnapshot(String token) = 6;
    Bundle prepareNfcProvision(String token, String nfcid2) = 7;
    Bundle nfcProvisionStatus() = 8;
    Bundle readNfcProvisionPlan(String token) = 9;
    Bundle applyNfcProvision(String token) = 10;
    Bundle reloadNfcProvision(String token) = 11;
    Bundle rollbackNfcProvision(String token) = 12;
    Bundle resumeNfcProvision(in Bundle savedPlan) = 13;
    void destroy() = 16777114;
}
