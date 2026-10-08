import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Read-only shell inventory. Never registers services, callbacks, routes or NCI commands. */
public final class NfcIdentityAudit {
    private static final String LAB_PACKAGE = "io.github.umislat.hceflab";
    private static final String LAB_SERVICE = LAB_PACKAGE + ".LabService";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments supported");
        int uid = ((Number) Class.forName("android.os.Process").getMethod("myUid").invoke(null)).intValue();
        if (uid != 2000) throw new SecurityException("Run only as shell UID 2000");
        System.out.println("uid=" + uid);

        Class<?> adapterType = Class.forName("android.nfc.INfcAdapter");
        Class<?> felicaType = Class.forName("android.nfc.INfcFCardEmulation");
        for (Class<?> type : new Class<?>[] {adapterType, felicaType}) {
            System.out.println("INTERFACE " + type.getName());
            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method method : methods) System.out.println("  " + method);
        }

        Class<?> validation = Class.forName("android.nfc.cardemulation.NfcFCardEmulation");
        for (String candidate : new String[] {"02FE6C6162323031", "012E6C6162323031"}) {
            report("local.isValidNfcid2(" + candidate + ")",
                    () -> validation.getMethod("isValidNfcid2", String.class).invoke(null, candidate));
        }
        for (String candidate : new String[] {"4000", "88B4", "40FF"}) {
            report("local.isValidSystemCode(" + candidate + ")",
                    () -> validation.getMethod("isValidSystemCode", String.class).invoke(null, candidate));
        }

        Class<?> binderType = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "nfc");
        if (binder == null) throw new IllegalStateException("NFC service unavailable");
        Object adapter = Class.forName("android.nfc.INfcAdapter$Stub")
                .getMethod("asInterface", binderType).invoke(null, binder);
        report("nfc.state", () -> adapterType.getMethod("getState").invoke(adapter));

        Object felica = adapterType.getMethod("getNfcFCardEmulationInterface").invoke(adapter);
        if (felica == null) throw new IllegalStateException("NFC-F interface unavailable");
        Class<?> componentType = Class.forName("android.content.ComponentName");
        Object lab = componentType.getConstructor(String.class, String.class)
                .newInstance(LAB_PACKAGE, LAB_SERVICE);
        report("nfcF.maxSystemCodes",
                () -> felicaType.getMethod("getMaxNumOfRegisterableSystemCodes").invoke(felica));
        report("lab.listing", () -> inspectLab(felicaType, felica, componentType, lab));
        report("lab.getNfcid2AsShell", () -> felicaType
                .getMethod("getNfcid2ForService", int.class, componentType).invoke(felica, 0, lab));
        report("lab.getSystemCodeAsShell", () -> felicaType
                .getMethod("getSystemCodeForService", int.class, componentType).invoke(felica, 0, lab));
        System.out.println("DONE: no setter, enable, callback, NCI, permission or file-state write");
    }

    private static String inspectLab(Class<?> iface, Object service, Class<?> componentType,
            Object lab) throws Exception {
        Object result = iface.getMethod("getNfcFServices", int.class).invoke(service, 0);
        if (result == null) return "unavailable";
        Class<?> infoType = Class.forName("android.nfc.cardemulation.NfcFServiceInfo");
        for (Object entry : (List<?>) result) {
            Object component = infoType.getMethod("getComponent").invoke(entry);
            if (!lab.equals(component)) continue;
            // Only the lab is printed; other applications can contain real card identifiers.
            String idm = (String) infoType.getMethod("getNfcid2").invoke(entry);
            String sc = (String) infoType.getMethod("getSystemCode").invoke(entry);
            String pmm = (String) infoType.getMethod("getT3tPmm").invoke(entry);
            return "present uid=" + infoType.getMethod("getUid").invoke(entry)
                    + " expectedSynthetic=" + ("02FE6C6162323031".equals(idm)
                    && "4000".equals(sc) && "00F1000000014300".equals(pmm));
        }
        return "absent";
    }

    private interface Query { Object read() throws Exception; }

    private static void report(String name, Query query) {
        try { System.out.println(name + "=" + query.read()); }
        catch (Exception exception) {
            Throwable cause = exception;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            System.out.println(name + "=ERROR " + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }
}
