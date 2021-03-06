/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.durabletask;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Plugin;
import hudson.Proc;
import hudson.Launcher;
import jenkins.model.Jenkins;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.OutputStream;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Runs a Powershell script
 */
public final class PowershellScript extends FileMonitoringTask {
    private final String script;
    private boolean capturingOutput;

    @DataBoundConstructor public PowershellScript(String script) {
        this.script = script;
    }
    
    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        List<String> args = new ArrayList<String>();
        PowershellController c = new PowershellController(ws);


        OutputStream outputFile = c.getOutputFile(ws).write();
        OutputStream logFile    = c.getLogFile(ws).write();
        
        List<OutputStream> stderrStreams = new ArrayList<OutputStream>();
        List<OutputStream> stdoutStreams = new ArrayList<OutputStream>();
        
        String cmd;
        if (capturingOutput) {
            stdoutStreams.add(outputFile);

            stderrStreams.add(outputFile);
            stderrStreams.add(logFile);
            cmd = String.format("$PSDefaultParameterValues[\"*:Encoding\"] = \"UTF8\"; & \"%s\"; Write-Error ($error -join [Environment]::NewLine)", quote(c.getPowershellMainFile(ws)));
        } else {
            stdoutStreams.add(logFile);
            stderrStreams.add(logFile);
            cmd = String.format("$PSDefaultParameterValues[\"*:Encoding\"] = \"UTF8\"; & \"%s\";", quote(c.getPowershellMainFile(ws)));
        }

        // Write the script and execution wrapper to powershell files in the workspace
        c.getPowershellMainFile(ws).write("try {\r\n" + script + "\r\n} catch {\r\nWrite-Output $_; exit 1;\r\n}\r\nif ($LastExitCode -ne $null -and $LastExitCode -ne 0) {\r\nexit $LastExitCode;\r\n} elseif ($error.Count -gt 0 -or !$?) {\r\nexit 1;\r\n} else {\r\nexit 0;\r\n}", "UTF-8");
        c.getPowershellWrapperFile(ws).write(cmd, "UTF-8");

        if (launcher.isUnix()) {
            // Open-Powershell does not support ExecutionPolicy
            args.addAll(Arrays.asList("powershell", "-NonInteractive", "-File", c.getPowershellWrapperFile(ws).getRemote()));
        } else {
            args.addAll(Arrays.asList("powershell.exe", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", c.getPowershellWrapperFile(ws).getRemote()));    
        }

        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);

        // redirect output
        OutputStreamMultiplexer stdout = new OutputStreamMultiplexer(stdoutStreams);
        OutputStreamMultiplexer stderr = new OutputStreamMultiplexer(stderrStreams);
        ps.stderr(stdout);
        ps.stderr(stderr);

        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+\\\\", "") + "] Running PowerShell script");
        c.addProcess(ps.start());
        return c;
    }
    
    private static String quote(FilePath f) {
        return f.getRemote().replace("%", "%%");
    }

    public static final class PowershellController extends FileMonitoringController {

        Proc process;

        private PowershellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getPowershellMainFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellMain.ps1");
        }
        
        public FilePath getPowershellWrapperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellWrapper.ps1");
        }

        protected void addProcess(Proc theProcess) {
            process = theProcess;
        }

        public Proc getProcess() {
            return process;
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.PowershellScript_powershell();
        }

    }

    private static class OutputStreamMultiplexer extends OutputStream {

        private ArrayList<OutputStream> streams;

        public OutputStreamMultiplexer(List<OutputStream> outStreams) {
            this.streams = new ArrayList<OutputStream>(outStreams);
        }


        @Override
        public void close() throws IOException{
            for(OutputStream stream : streams) {
                stream.close();
            }
            streams.clear();
        }

        @Override
        public void flush() throws IOException {
            for(OutputStream stream : streams) {
                stream.flush();
            }
        }

        @Override
        public void write(byte[] b) throws IOException {
            for(OutputStream stream : streams) {
                stream.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for(OutputStream stream : streams) {
                stream.write(b, off, len);
            }
        }

        @Override
        public void write(int data) throws IOException {
            for(OutputStream stream : streams) {
                stream.write(data);
            }
        }
    }

}
