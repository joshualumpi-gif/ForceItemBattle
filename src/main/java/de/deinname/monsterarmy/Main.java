package de.deinname.monsterarmy;

import org.bukkit.plugin.java.JavaPlugin;
import org.example.TimerPlugin;

public class Main extends JavaPlugin {

    private static Main instance;
    private ForceModule forceModule;

    @Override
    public void onEnable() {
        instance = this;

        if (getServer().getPluginManager().getPlugin("Timer") == null) {
            getLogger().severe("Timer Plugin nicht gefunden! Disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.forceModule = new ForceModule(this);

        getServer().getPluginManager().registerEvents(new ForceListener(this), this);

        ForceCommand cmd = new ForceCommand(this);

        getCommand("force").setExecutor(cmd);
        getCommand("force").setTabCompleter(cmd);

        getCommand("result").setExecutor(cmd);
        getCommand("result").setTabCompleter(cmd);

        getCommand("reset").setExecutor(cmd);
        getCommand("reset").setTabCompleter(cmd);

        getLogger().info("MonsterArmy (Force) aktiviert!");
    }

    @Override
    public void onDisable() {
        if (forceModule != null) {
            forceModule.disable();
        }
    }

    public ForceModule getModule() { return forceModule; }
}