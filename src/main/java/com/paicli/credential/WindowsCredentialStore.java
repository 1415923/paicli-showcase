package com.paicli.credential;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Windows Credential Manager generic-credential backend. */
public final class WindowsCredentialStore implements CredentialStore {
    static final int CRED_TYPE_GENERIC = 1;
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    static final int ERROR_NOT_FOUND = 1168;
    static final int MAX_BLOB_BYTES = 512;
    private final NativeApi api;

    public WindowsCredentialStore() {
        if (!isWindows()) throw new CredentialStoreException(UnsupportedCredentialStore.CODE);
        this.api = Native.load("Advapi32", NativeApi.class);
    }
    WindowsCredentialStore(NativeApi api) { this.api = java.util.Objects.requireNonNull(api); }

    @Override public void put(CredentialRef reference, char[] secret) {
        java.util.Objects.requireNonNull(reference); java.util.Objects.requireNonNull(secret);
        if (secret.length == 0 || secret.length * 2 > MAX_BLOB_BYTES)
            throw new CredentialStoreException("CREDENTIAL_VALUE_INVALID");
        Memory blob = new Memory((long) secret.length * 2);
        try {
            for (int i=0;i<secret.length;i++) blob.setShort((long)i*2,(short)secret[i]);
            Credential value=new Credential(); value.Type=CRED_TYPE_GENERIC;
            value.TargetName=new WString(reference.value()); value.CredentialBlobSize=secret.length*2;
            value.CredentialBlob=blob; value.Persist=CRED_PERSIST_LOCAL_MACHINE;
            value.UserName=new WString("PaiCLI"); value.write();
            if(!api.CredWriteW(value,0)) fail("CREDENTIAL_STORE_WRITE_FAILED");
        } finally { blob.clear(); }
    }

    @Override public Optional<char[]> get(CredentialRef reference) {
        java.util.Objects.requireNonNull(reference);
        PointerByReference output=new PointerByReference();
        if(!api.CredReadW(new WString(reference.value()),CRED_TYPE_GENERIC,0,output)){
            if(Native.getLastError()==ERROR_NOT_FOUND)return Optional.empty();
            fail("CREDENTIAL_STORE_READ_FAILED");
        }
        Pointer pointer=output.getValue();
        Pointer secretPointer=null; int secretBytes=0;
        try {
            Credential value=new Credential(pointer); value.read(); int bytes=value.CredentialBlobSize;
            if(bytes<=0||bytes>MAX_BLOB_BYTES||(bytes&1)!=0||value.CredentialBlob==null)
                throw new CredentialStoreException("CREDENTIAL_VALUE_INVALID");
            secretPointer=value.CredentialBlob; secretBytes=bytes;
            char[] secret=new char[bytes/2];
            for(int i=0;i<secret.length;i++)secret[i]=(char)(value.CredentialBlob.getShort((long)i*2)&0xffff);
            return Optional.of(secret);
        } finally {
            try { if(secretPointer!=null&&secretBytes>0)secretPointer.clear(secretBytes); }
            finally { if(pointer!=null)api.CredFree(pointer); }
        }
    }

    @Override public boolean delete(CredentialRef reference) {
        java.util.Objects.requireNonNull(reference);
        if(api.CredDeleteW(new WString(reference.value()),CRED_TYPE_GENERIC,0))return true;
        if(Native.getLastError()==ERROR_NOT_FOUND)return false;
        fail("CREDENTIAL_STORE_DELETE_FAILED"); return false;
    }
    @Override public Status status(CredentialRef reference) {
        Optional<char[]> value;
        try{value=get(reference);}catch(CredentialStoreException error){return new Status(State.ERROR,"windows-credential-manager");}
        value.ifPresent(secret->Arrays.fill(secret,'\0'));
        return new Status(value.isPresent()?State.PRESENT:State.MISSING,"windows-credential-manager");
    }
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
    private static void fail(String code){throw new CredentialStoreException(code);}

    interface NativeApi extends StdCallLibrary {
        boolean CredWriteW(Credential credential,int flags);
        boolean CredReadW(WString target,int type,int flags,PointerByReference credential);
        boolean CredDeleteW(WString target,int type,int flags);
        void CredFree(Pointer buffer);
    }
    @Structure.FieldOrder({"dwLowDateTime","dwHighDateTime"})
    public static class FileTime extends Structure { public int dwLowDateTime; public int dwHighDateTime; }
    @Structure.FieldOrder({"Flags","Type","TargetName","Comment","LastWritten","CredentialBlobSize","CredentialBlob","Persist","AttributeCount","Attributes","TargetAlias","UserName"})
    public static class Credential extends Structure {
        public int Flags; public int Type; public WString TargetName; public WString Comment;
        public FileTime LastWritten=new FileTime(); public int CredentialBlobSize; public Pointer CredentialBlob;
        public int Persist; public int AttributeCount; public Pointer Attributes;
        public WString TargetAlias; public WString UserName;
        public Credential(){super();} public Credential(Pointer pointer){super(pointer);}
    }
}
