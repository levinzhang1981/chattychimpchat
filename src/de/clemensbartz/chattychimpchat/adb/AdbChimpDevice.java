/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.clemensbartz.chattychimpchat.adb;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.annotations.Nullable;
import de.clemensbartz.chattychimpchat.ChimpManager;
import de.clemensbartz.chattychimpchat.adb.LinearInterpolator.Point;
import de.clemensbartz.chattychimpchat.core.IChimpImage;
import de.clemensbartz.chattychimpchat.core.IChimpDevice;
import de.clemensbartz.chattychimpchat.core.IChimpView;
import de.clemensbartz.chattychimpchat.core.IMultiSelector;
import de.clemensbartz.chattychimpchat.core.ISelector;
import de.clemensbartz.chattychimpchat.core.PhysicalButton;
import de.clemensbartz.chattychimpchat.core.TouchPressType;
import de.clemensbartz.chattychimpchat.hierarchyviewer.HierarchyViewer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdbChimpDevice implements IChimpDevice {
    private static final Logger LOG = Logger.getLogger(AdbChimpDevice.class.getName());

    private static final String[] ZERO_LENGTH_STRING_ARRAY = new String[0];
    private static final long MANAGER_CREATE_TIMEOUT_MS = 30 * 1000; // 30 seconds
    private static final long MANAGER_CREATE_WAIT_TIME_MS = 1000; // wait 1 second

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final IDevice device;
    private ChimpManager manager;

    public AdbChimpDevice(IDevice device) throws TimeoutException, IOException, AdbCommandRejectedException,
        InterruptedException
    {
        this.device = device;
        this.manager = createManager("127.0.0.1", 12345);

        Preconditions.checkNotNull(this.manager);
    }

    @Override
    public ChimpManager getManager() {
        return manager;
    }

    @Override
    public void dispose() throws IOException{
        manager.quit();
        manager.close();
        executor.shutdown();
        manager = null;
    }

    @Override
    public HierarchyViewer getHierarchyViewer() {
        return new HierarchyViewer(device);
    }

    private void executeAsyncCommand(final String command,
            final LoggingOutputReceiver logger) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    device.executeShellCommand(command, logger);
                } catch (TimeoutException e) {
                    LOG.log(Level.SEVERE, "Error starting command: " + command, e);
                    throw new RuntimeException(e);
                } catch (AdbCommandRejectedException e) {
                    LOG.log(Level.SEVERE, "Error starting command: " + command, e);
                    throw new RuntimeException(e);
                } catch (ShellCommandUnresponsiveException e) {
                    // This happens a lot
                    LOG.log(Level.INFO, "Error starting command: " + command, e);
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Error starting command: " + command, e);
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private ChimpManager createManager(String address, int port) throws TimeoutException,
            AdbCommandRejectedException, IOException, UnknownHostException, InterruptedException {
        device.createForward(port, port);

        String command = "monkey --port " + port;
        executeAsyncCommand(command, new LoggingOutputReceiver(LOG, Level.FINE));

        // Sleep for a second to give the command time to execute.
        Thread.sleep(1000);

        InetAddress addr = InetAddress.getByName(address);

        // We have a tough problem to solve here.  "monkey" on the device gives us no indication
        // when it has started up and is ready to serve traffic.  If you try too soon, commands
        // will fail.  To remedy this, we will keep trying until a single command (in this case,
        // wake) succeeds.
        boolean success = false;
        ChimpManager mm = null;
        long start = System.currentTimeMillis();

        while (!success) {
            long now = System.currentTimeMillis();
            long diff = now - start;
            if (diff > MANAGER_CREATE_TIMEOUT_MS) {
                LOG.severe("Timeout while trying to create chimp mananger");
                return null;
            }

            Thread.sleep(MANAGER_CREATE_WAIT_TIME_MS);

            Socket monkeySocket = new Socket(addr, port);

            mm = new ChimpManager(monkeySocket);

            mm.wake();
            success = true;
        }

        return mm;
    }

    @Override
    public IChimpImage takeSnapshot() throws TimeoutException, AdbCommandRejectedException, IOException{
        return new AdbChimpImage(device.getScreenshot());
    }

    @Override
    public String getSystemProperty(String key) {
        return device.getProperty(key);
    }

    @Override
    public String getProperty(String key) throws IOException {
        return manager.getVariable(key);
    }

    @Override
    public Collection<String> getPropertyList() throws IOException {
        return manager.listVariable();
    }

    @Override
    public void wake() throws IOException {
        manager.wake();
    }

    private String shell(String... args) throws TimeoutException, ShellCommandUnresponsiveException,
            AdbCommandRejectedException, IOException
    {
        StringBuilder cmd = new StringBuilder();
        for (String arg : args) {
            cmd.append(arg).append(" ");
        }
        return shell(cmd.toString());
    }

    @Override
    public String shell(String cmd) throws TimeoutException, ShellCommandUnresponsiveException,
        AdbCommandRejectedException, IOException
    {
        // 5000 is the default timeout from the ddmlib.
        // This timeout arg is needed to the backwards compatibility.
        return shell(cmd, 5000);
    }

    @Override
    public String shell(String cmd, int timeout) throws TimeoutException, ShellCommandUnresponsiveException,
        AdbCommandRejectedException, IOException
    {
        CommandOutputCapture capture = new CommandOutputCapture();
        device.executeShellCommand(cmd, capture, timeout);
        return capture.toString();
    }

    @Override
    public boolean installPackage(String path) throws InstallException {
        String result = device.installPackage(path, true);
        if (result != null) {
            LOG.log(Level.SEVERE, "Got error installing package: "+ result);
            return false;
        }
        return true;
    }

    @Override
    public boolean removePackage(String packageName) throws InstallException {
        String result = device.uninstallPackage(packageName);
        if (result != null) {
            LOG.log(Level.SEVERE, "Got error uninstalling package "+ packageName + ": " +
                    result);
            return false;
        }
        return true;
    }

    @Override
    public void press(String keyName, TouchPressType type) throws IOException {
        switch (type) {
            case DOWN_AND_UP:
                manager.press(keyName);
                break;
            case DOWN:
                manager.keyDown(keyName);
                break;
            case UP:
                manager.keyUp(keyName);
                break;
        }
    }

    @Override
    public void press(PhysicalButton key, TouchPressType type) throws IOException {
      press(key.getKeyName(), type);
    }

    @Override
    public void type(String string) throws IOException {
        manager.type(string);
    }

    @Override
    public void touch(int x, int y, TouchPressType type) throws IOException {
        switch (type) {
            case DOWN:
                manager.touchDown(x, y);
                break;
            case UP:
                manager.touchUp(x, y);
                break;
            case DOWN_AND_UP:
                manager.tap(x, y);
                break;
            case MOVE:
                manager.touchMove(x, y);
                break;
        }
    }

    @Override
    public void reboot(String into) throws TimeoutException, AdbCommandRejectedException, IOException{
        device.reboot(into);
    }

    @Override
    public void startActivity(String uri, String action, String data, String mimetype,
            Collection<String> categories, Map<String, Object> extras, String component,
            int flags) throws IOException, InterruptedException, AdbCommandRejectedException, TimeoutException,
            ShellCommandUnresponsiveException
    {
        List<String> intentArgs = buildIntentArgString(uri, action, data, mimetype, categories,
                extras, component, flags);
        shell(Lists.asList("am", "start",
                intentArgs.toArray(ZERO_LENGTH_STRING_ARRAY)).toArray(ZERO_LENGTH_STRING_ARRAY));
    }

    @Override
    public void broadcastIntent(String uri, String action, String data, String mimetype,
            Collection<String> categories, Map<String, Object> extras, String component,
            int flags) throws IOException, InterruptedException, AdbCommandRejectedException, TimeoutException,
            ShellCommandUnresponsiveException
    {
        List<String> intentArgs = buildIntentArgString(uri, action, data, mimetype, categories,
                extras, component, flags);
        shell(Lists.asList("am", "broadcast",
                intentArgs.toArray(ZERO_LENGTH_STRING_ARRAY)).toArray(ZERO_LENGTH_STRING_ARRAY));
    }

    private static boolean isNullOrEmpty(@Nullable String string) {
        return string == null || string.length() == 0;
    }

    private List<String> buildIntentArgString(String uri, String action, String data, String mimetype,
            Collection<String> categories, Map<String, Object> extras, String component,
            int flags) {
        List<String> parts = Lists.newArrayList();

        // from adb docs:
        //<INTENT> specifications include these flags:
        //    [-a <ACTION>] [-d <DATA_URI>] [-t <MIME_TYPE>]
        //    [-c <CATEGORY> [-c <CATEGORY>] ...]
        //    [-e|--es <EXTRA_KEY> <EXTRA_STRING_VALUE> ...]
        //    [--esn <EXTRA_KEY> ...]
        //    [--ez <EXTRA_KEY> <EXTRA_BOOLEAN_VALUE> ...]
        //    [-e|--ei <EXTRA_KEY> <EXTRA_INT_VALUE> ...]
        //    [-n <COMPONENT>] [-f <FLAGS>]
        //    [<URI>]

        if (!isNullOrEmpty(action)) {
            parts.add("-a");
            parts.add(action);
        }

        if (!isNullOrEmpty(data)) {
            parts.add("-d");
            parts.add(data);
        }

        if (!isNullOrEmpty(mimetype)) {
            parts.add("-t");
            parts.add(mimetype);
        }

        // Handle categories
        for (String category : categories) {
            parts.add("-c");
            parts.add(category);
        }

        // Handle extras
        for (Entry<String, Object> entry : extras.entrySet()) {
            // Extras are either boolean, string, or int.  See which we have
            Object value = entry.getValue();
            String valueString;
            String arg;
            if (value instanceof Integer) {
                valueString = Integer.toString((Integer) value);
                arg = "--ei";
            } else if (value instanceof Boolean) {
                valueString = Boolean.toString((Boolean) value);
                arg = "--ez";
            } else {
                // treat is as a string.
                valueString = value.toString();
                arg = "--es";
            }
            parts.add(arg);
            parts.add(entry.getKey());
            parts.add(valueString);
        }

        if (!isNullOrEmpty(component)) {
            parts.add("-n");
            parts.add(component);
        }

        if (flags != 0) {
            parts.add("-f");
            parts.add(Integer.toString(flags));
        }

        if (!isNullOrEmpty(uri)) {
            parts.add(uri);
        }

        return parts;
    }

    @Override
    public Map<String, Object> instrument(String packageName, Map<String, Object> args) throws IOException,
            InterruptedException, AdbCommandRejectedException, TimeoutException, ShellCommandUnresponsiveException
    {
        List<String> shellCmd = Lists.newArrayList("am", "instrument", "-w", "-r");
        for (Entry<String, Object> entry: args.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (key != null && value != null) {
                shellCmd.add("-e");
                shellCmd.add(key);
                shellCmd.add(value.toString());
            }
        }
        shellCmd.add(packageName);
        String result = shell(shellCmd.toArray(ZERO_LENGTH_STRING_ARRAY));
        return convertInstrumentResult(result);
    }

    /**
     * Convert the instrumentation result into it's Map representation.
     *
     * @param result the result string
     * @return the new map
     */
    @VisibleForTesting
    /* package */ static Map<String, Object> convertInstrumentResult(String result) {
        Map<String, Object> map = Maps.newHashMap();
        Pattern pattern = Pattern.compile("^INSTRUMENTATION_(\\w+): ", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(result);

        int previousEnd = 0;
        String previousWhich = null;

        while (matcher.find()) {
            if ("RESULT".equals(previousWhich)) {
                String resultLine = result.substring(previousEnd, matcher.start()).trim();
                // Look for the = in the value, and split there
                int splitIndex = resultLine.indexOf("=");
                String key = resultLine.substring(0, splitIndex);
                String value = resultLine.substring(splitIndex + 1);

                map.put(key, value);
            }

            previousEnd = matcher.end();
            previousWhich = matcher.group(1);
        }
        if ("RESULT".equals(previousWhich)) {
            String resultLine = result.substring(previousEnd, matcher.start()).trim();
            // Look for the = in the value, and split there
            int splitIndex = resultLine.indexOf("=");
            String key = resultLine.substring(0, splitIndex);
            String value = resultLine.substring(splitIndex + 1);

            map.put(key, value);
        }
        return map;
    }

    @Override
    public void drag(int startx, int starty, int endx, int endy, int steps, long ms) {
        final long iterationTime = ms / steps;

        LinearInterpolator lerp = new LinearInterpolator(steps);
        LinearInterpolator.Point start = new LinearInterpolator.Point(startx, starty);
        LinearInterpolator.Point end = new LinearInterpolator.Point(endx, endy);
        lerp.interpolate(start, end, new LinearInterpolator.Callback() {
            @Override
            public void step(Point point) {
                try {
                    manager.touchMove(point.getX(), point.getY());
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Error sending drag start event", e);
                }

                try {
                    Thread.sleep(iterationTime);
                } catch (InterruptedException e) {
                    LOG.log(Level.SEVERE, "Error sleeping", e);
                }
            }

            @Override
            public void start(Point point) {
                try {
                    manager.touchDown(point.getX(), point.getY());
                    manager.touchMove(point.getX(), point.getY());
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Error sending drag start event", e);
                }

                try {
                    Thread.sleep(iterationTime);
                } catch (InterruptedException e) {
                    LOG.log(Level.SEVERE, "Error sleeping", e);
                }
            }

            @Override
            public void end(Point point) {
                try {
                    manager.touchMove(point.getX(), point.getY());
                    manager.touchUp(point.getX(), point.getY());
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Error sending drag end event", e);
                }
            }
        });
    }


    @Override
    public Collection<String> getViewIdList() throws IOException {
        return manager.listViewIds();
    }

    @Override
    public IChimpView getView(ISelector selector) {
        return selector.getView(manager);
    }

    @Override
    public Collection<IChimpView> getViews(IMultiSelector selector) throws IOException {
        return selector.getViews(manager);
    }

    @Override
    public IChimpView getRootView() throws IOException {
        return manager.getRootView();
    }
}
