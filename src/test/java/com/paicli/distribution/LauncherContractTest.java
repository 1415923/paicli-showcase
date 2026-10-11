package com.paicli.distribution;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LauncherContractTest {
    @Test
    void launchersUseFixedRelativeJarAndPreserveArguments() throws Exception {
        String ps = Files.readString(Path.of("src/dist/bin/paicli.ps1"));
        String cmd = Files.readString(Path.of("src/dist/bin/paicli.cmd"));
        String sh = Files.readString(Path.of("src/dist/bin/paicli"));
        assertTrue(ps.contains("@($args)"));
        assertTrue(ps.contains("$javaArguments = @('-jar', $jar) + @($args)"));
        assertTrue(ps.contains("& $java.Source @javaArguments"));
        assertTrue(ps.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(ps.contains("exit $exitCode"));
        assertTrue(ps.contains("$currentLocation = Get-Location"));
        assertTrue(ps.contains("$currentLocation.Provider.Name -ne 'FileSystem'"));
        assertTrue(ps.contains("[System.IO.Path]::GetFullPath("));
        assertTrue(ps.contains("[System.IO.Directory]::Exists($launchWorkingDirectory)"));
        assertFalse(ps.contains("ConvertTo-PaiCliNativeArgument"));
        assertFalse(ps.contains("ProcessStartInfo"));
        assertFalse(ps.contains("[System.Diagnostics.Process]::Start"));
        assertFalse(ps.contains("Start-Process"));
        assertFalse(ps.contains("WaitForExit"));
        assertFalse(ps.contains("RedirectStandardInput"));
        assertFalse(ps.contains("RedirectStandardOutput"));
        assertFalse(ps.contains("RedirectStandardError"));
        assertFalse(ps.contains("Register-ObjectEvent"));
        assertFalse(ps.contains("Register-EngineEvent"));
        assertFalse(ps.contains("trap "));
        assertTrue(cmd.contains("%*"));
        assertTrue(cmd.contains("exit /b %PAICLI_EXIT%"));
        assertTrue(sh.contains("\"$@\""));
        assertTrue(sh.contains("exec java -jar"));
        assertTrue(ps.contains("lib/paicli-1.2.0.jar"));
        assertTrue(cmd.contains("lib\\paicli-1.2.0.jar"));
        assertTrue(sh.contains("lib/paicli-1.2.0.jar"));
        assertFalse(ps.contains("Invoke-Expression"));
        assertFalse(sh.contains("eval "));
    }

    @Test
    void assemblySetsPosixModeAndVersionedLayout() throws Exception {
        String descriptor = Files.readString(Path.of("src/assembly/distribution.xml"));
        assertTrue(descriptor.contains("<fileMode>0755</fileMode>"));
        assertTrue(descriptor.contains("<outputDirectory>lib</outputDirectory>"));
        assertTrue(descriptor.contains("paicli-${project.version}.jar"));
    }
}
