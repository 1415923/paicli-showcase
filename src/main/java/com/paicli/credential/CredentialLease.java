package com.paicli.credential;

import java.util.Arrays;

/** Short-lived mutable credential value. */
public final class CredentialLease implements AutoCloseable {
    public enum Source { ENVIRONMENT, OS_STORE }
    private final char[] value; private final Source source; private boolean closed;
    CredentialLease(char[] value,Source source){this.value=java.util.Objects.requireNonNull(value);this.source=java.util.Objects.requireNonNull(source);}
    public char[] value(){if(closed)throw new IllegalStateException("credential lease is closed");return value;}
    public Source source(){return source;}
    @Override public void close(){if(!closed)Arrays.fill(value,'\0');closed=true;}
}
