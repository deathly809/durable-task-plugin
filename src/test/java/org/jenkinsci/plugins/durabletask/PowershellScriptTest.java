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

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.containsString;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import java.util.Properties;

public class PowershellScriptTest {	
    @Rule public JenkinsRule j = new JenkinsRule();

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;
    private String encoding = "US-ASCII";

    @Before public void vars() {
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
        Properties properties = System.getProperties();
        String pathSeparator = properties.getProperty("path.separator");
        String[] paths = System.getenv("PATH").split(pathSeparator);
        boolean powershellExists = false;
        String cmd = launcher.isUnix()?"powershell":"powershell.exe";
        for (String p : paths) {
            File f = new File(p, cmd);
            if (f.exists()) {
                powershellExists = true;
                break;
            }
        }
        Assume.assumeTrue("This test should only run if powershell is available", powershellExists == true);
    }

    private void outputEquality(String cmd, String cmp, boolean exitSuccess, boolean stdOut ) throws Exception {
        outputEquality(cmd, cmp, exitSuccess, stdOut, new EnvVars());
    }

    private void outputEquality(String cmd, String cmp, boolean exitSuccess, boolean stdOut, EnvVars env) throws Exception {
        DurableTask task = new PowershellScript(cmd);
        if(stdOut) {
            task.captureOutput();
        }

        Controller c = task.launch(env, ws, launcher, listener);
        
        ByteArrayOutputStream errorBaos = new ByteArrayOutputStream();
        TeeOutputStream tos = new TeeOutputStream(errorBaos, System.err);

        while (c.exitStatus(ws, launcher) == null) {
            c.writeLog(ws, tos);
            Thread.sleep(100);
        }
        c.writeLog(ws, tos);

        if(exitSuccess) {
            assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        } else {
            assertTrue(c.exitStatus(ws, launcher).intValue() != 0);
        }

        if(stdOut) {
            String output = new String(c.getOutput(ws, launcher),encoding).trim();
            assertEquals(output, cmp);
        } else {
            String log = errorBaos.toString(encoding);
            assertTrue(log, log.contains(cmp));
        }

        // You must call stop or cleanup can fail
        c.stop(ws, launcher);
        c.cleanup(ws);
    }

    
    @Test public void verbose() throws Exception {
        String msg = "Hello, World!";
        String cmd = String.format("$VerbosePreference = \"Continue\"; Write-Verbose \"%s\"", msg);
        boolean exitSuccess = true;
        boolean stdOut = true;
        outputEquality(cmd, msg, exitSuccess, stdOut);
    }

    @Test public void explicitExit() throws Exception {
        String msg = "Hello, World!";
        String cmd = String.format("Write-Output \"%s\"; exit 1;", msg);
        boolean exitSuccess = false;
        boolean stdOut = true;
        outputEquality(cmd, msg, exitSuccess, stdOut);
    }
    
    @Test public void implicitExit() throws Exception {
        String msg = "Success!";
        String cmd = String.format("Write-Output \"%s\";", msg);
        boolean exitSuccess = true;
        boolean stdOut = false;
        outputEquality(cmd, msg , exitSuccess, stdOut);
    }
    
    @Test public void implicitError() throws Exception {
        String msg = "";
        String cmd = "MyBogus-Cmdlet";
        boolean exitSuccess = false;
        boolean stdOut = false;
        outputEquality(cmd, msg , exitSuccess, stdOut);
    }
    
    @Test public void explicitError() throws Exception {
        String msg = "explicit error";
        String cmd = String.format("Write-Output \"Hello, World!\"; throw \"%s\";", msg);
        boolean exitSuccess = false;
        boolean stdOut = false;
        outputEquality(cmd, msg, exitSuccess, stdOut);
    }
    

    @Test public void echoEnvVar() throws Exception {
        String msg = "envvar=power$hell";
        String cmd = "echo envvar=$env:MYVAR";
        boolean exitSuccess = true;
        boolean stdOut = true;
        outputEquality(cmd, msg, exitSuccess, stdOut, new EnvVars("MYVAR", "power$hell"));
    }

}
