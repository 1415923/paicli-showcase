package com.paicli.credential;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WindowsCredentialStoreTest {
    @Test void nativeBackendWritesReadsAndDeletesGenericCredentialWithoutStringSecret() {
        FakeNative api = new FakeNative();
        WindowsCredentialStore store = new WindowsCredentialStore(api);
        CredentialRef ref = new CredentialRef("PaiCLI/provider/deepseek/default");
        char[] secret = "native-fixture".toCharArray();

        store.put(ref, secret);
        assertArrayEquals(secret, api.stored);
        assertEquals(WindowsCredentialStore.CRED_TYPE_GENERIC, api.type);
        assertEquals(WindowsCredentialStore.CRED_PERSIST_LOCAL_MACHINE, api.persist);

        char[] loaded = store.get(ref).orElseThrow();
        assertArrayEquals(secret, loaded);
        Arrays.fill(loaded, '\0');
        assertTrue(store.delete(ref));
        assertTrue(api.freed);
        assertTrue(api.blobClearedBeforeFree);
    }

    private static final class FakeNative implements WindowsCredentialStore.NativeApi {
        private char[] stored;
        private int type;
        private int persist;
        private Memory resultMemory;
        private Memory blobMemory;
        private boolean freed;
        private boolean blobClearedBeforeFree;

        @Override public boolean CredWriteW(WindowsCredentialStore.Credential credential, int flags) {
            credential.read();
            type = credential.Type;
            persist = credential.Persist;
            stored = new char[credential.CredentialBlobSize / 2];
            for (int index = 0; index < stored.length; index++) {
                stored[index] = (char) (credential.CredentialBlob.getShort((long) index * 2) & 0xffff);
            }
            return true;
        }

        @Override public boolean CredReadW(WString target, int requestedType, int flags,
                PointerByReference output) {
            if (stored == null) return false;
            blobMemory = new Memory((long) stored.length * 2);
            for (int index = 0; index < stored.length; index++) {
                blobMemory.setShort((long) index * 2, (short) stored[index]);
            }
            WindowsCredentialStore.Credential credential = new WindowsCredentialStore.Credential();
            credential.Type = requestedType;
            credential.TargetName = target;
            credential.LastWritten = new WindowsCredentialStore.FileTime();
            credential.CredentialBlobSize = stored.length * 2;
            credential.CredentialBlob = blobMemory;
            credential.Persist = WindowsCredentialStore.CRED_PERSIST_LOCAL_MACHINE;
            credential.UserName = new WString("PaiCLI");
            credential.write();
            resultMemory = new Memory(credential.size());
            byte[] bytes = credential.getPointer().getByteArray(0, credential.size());
            resultMemory.write(0, bytes, 0, bytes.length);
            output.setValue(resultMemory);
            return true;
        }

        @Override public boolean CredDeleteW(WString target, int type, int flags) {
            if (stored == null) return false;
            Arrays.fill(stored, '\0');
            stored = null;
            return true;
        }

        @Override public void CredFree(Pointer buffer) {
            freed = true;
            blobClearedBeforeFree = true;
            for (byte value : blobMemory.getByteArray(0, Math.toIntExact(blobMemory.size()))) {
                if (value != 0) blobClearedBeforeFree = false;
            }
        }
    }
}
