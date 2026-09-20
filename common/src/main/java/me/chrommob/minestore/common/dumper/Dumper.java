package me.chrommob.minestore.common.dumper;

import com.google.gson.Gson;
import me.chrommob.minestore.common.MineStoreCommon;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public class Dumper {
    private final Gson gson = new Gson();

    /**
     * Writes the diagnostic bundle next to the plugin instead of uploading it.
     *
     * Upstream POSTs it to a third-party paste service, and the bundle is
     * {@link DumpData}: the whole config, including the store API key and the
     * weblistener secret, in clear text. It fires on every failed request, so
     * one bad response ships the store's credentials off the operator's
     * machine, and rotating the keys and failing again ships the new ones.
     * Measured on a live network: a rate-limited request did exactly that,
     * twice, minutes apart.
     *
     * The file is what the operator needs anyway; whoever wants to send it to
     * support can still do that, deliberately, after reading it.
     */
    public String dump(String log, MineStoreCommon plugin) {
        try {
            File target = new File(plugin.jarFile().getParentFile(),
                    "MineStore" + File.separator + "logs" + File.separator + "dump-" + System.currentTimeMillis() + ".json");
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.log("Could not create the dump folder at " + parent.getAbsolutePath());
                return null;
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
                writer.write(gson.toJson(new DumpData(log, plugin)));
            }
            plugin.log("Wrote the diagnostic dump to " + target.getAbsolutePath()
                    + ". It contains your API key and secret key, so read it before sharing it.");
            return target.getAbsolutePath();
        } catch (Exception failure) {
            plugin.debug(this.getClass(), failure.getMessage());
            return null;
        }
    }

    public String dump(boolean includeLog, MineStoreCommon plugin) {
        File logFile = plugin.jarFile().getParentFile();
        while (!logFile.getAbsolutePath().endsWith("plugins") && !logFile.getAbsolutePath().endsWith("mods")) {
            logFile = logFile.getParentFile();
        }
        logFile = new File(logFile.getParentFile(), "logs" + File.separator + "latest.log");
        if (logFile.exists() && includeLog) {
            try {
                StringBuilder fileData = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new FileReader(logFile));
                char[] buf = new char[1024];
                int numRead;
                while ((numRead = reader.read(buf)) != -1) {
                    String readData = String.valueOf(buf, 0, numRead);
                    fileData.append(readData);
                }
                reader.close();
                String log = fileData.toString();
                return dump(log, plugin);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (includeLog && !logFile.exists()) {
            return dump("Log file not found at " + logFile.getAbsolutePath(), plugin);
        }
        return dump("Log file not included", plugin);
    }
}
