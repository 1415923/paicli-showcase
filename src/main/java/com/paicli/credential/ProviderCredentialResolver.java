package com.paicli.credential;

import com.paicli.config.Config;
import com.paicli.provider.ProviderEndpointPolicy;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/** Environment override followed by approved OS credential storage. */
public final class ProviderCredentialResolver {
    public record ResolutionStatus(boolean present,String source,String backend) { }
    private final Function<String,String> environment; private final CredentialStore store;
    public ProviderCredentialResolver(Function<String,String> environment,CredentialStore store){this.environment=java.util.Objects.requireNonNull(environment);this.store=java.util.Objects.requireNonNull(store);}
    public static ProviderCredentialResolver systemDefault(){return new ProviderCredentialResolver(System::getenv,CredentialStores.systemDefault());}
    public CredentialLease resolve(Config config){
        String env=environment.apply(environmentName(config));
        if(env!=null&&!env.isBlank())return new CredentialLease(env.toCharArray(),CredentialLease.Source.ENVIRONMENT);
        Optional<char[]> stored=store.get(reference(config));
        if(stored.isPresent()){
            char[] value=stored.orElseThrow();
            if(value.length>0)return new CredentialLease(value,CredentialLease.Source.OS_STORE);
            Arrays.fill(value,'\0');
        }
        throw new CredentialStoreException("PROVIDER_CREDENTIAL_MISSING");
    }
    public ResolutionStatus status(Config config){
        String env=environment.apply(environmentName(config));
        if(env!=null&&!env.isBlank())return new ResolutionStatus(true,"environment","process-environment");
        CredentialStore.Status status=store.status(reference(config));
        String source=switch(status.state()){
            case PRESENT->"os-store";
            case MISSING->"missing";
            case UNSUPPORTED->"unsupported";
            case ERROR->"error";
        };
        return new ResolutionStatus(status.state()==CredentialStore.State.PRESENT,source,status.backend());
    }
    public CredentialStore store(){return store;}
    public static String environmentName(Config config){return ProviderEndpointPolicy.validateEnvironmentName(config.apiKeyEnv()!=null?config.apiKeyEnv():"deepseek".equals(config.providerPreset())?"DEEPSEEK_API_KEY":"OPENAI_API_KEY");}
    public static CredentialRef reference(Config config){
        if(config.credentialRef()!=null)return new CredentialRef(config.credentialRef());
        String profile="deepseek".equals(config.providerPreset())?"deepseek":"openai";
        return new CredentialRef("PaiCLI/provider/"+profile+"/default");
    }
}
