package com.paicli.credential;

import java.util.Locale;

public final class CredentialStores {
    private CredentialStores() { }
    public static CredentialStore systemDefault() {
        return systemDefault(System.getProperty("os.name",""),WindowsCredentialStore::new);
    }
    static CredentialStore systemDefault(String osName,
            java.util.function.Supplier<CredentialStore> windowsFactory) {
        if(!osName.toLowerCase(Locale.ROOT).contains("windows"))
            return new UnsupportedCredentialStore();
        try{return windowsFactory.get();}
        catch(RuntimeException|LinkageError unavailable){return new UnavailableCredentialStore();}
    }
}
