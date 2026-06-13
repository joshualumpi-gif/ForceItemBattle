package de.deinname.monsterarmy;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ForceCommand implements CommandExecutor, TabCompleter {

    private final Main plugin;

    public ForceCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cNur für Spieler!");
            return true;
        }
        Player p = (Player) sender;

        if (command.getName().equalsIgnoreCase("force")) {
            if (args.length >= 3 && args[0].equalsIgnoreCase("skip") && args[1].equalsIgnoreCase("give")) {
                if (p.hasPermission("force.admin") || p.isOp()) {
                    Player target = Bukkit.getPlayer(args[2]);
                    if (target != null) {
                        plugin.getModule().giveSkipItem(target, 1);
                        p.sendMessage("§aSkip an " + target.getName() + " gegeben.");
                        target.sendMessage("§aDu hast einen Skip erhalten!");
                    } else {
                        p.sendMessage("§cSpieler nicht gefunden.");
                    }
                } else {
                    p.sendMessage("§cKeine Rechte.");
                }
                return true;
            }
            if (p.hasPermission("force.admin") || p.isOp()) {
                plugin.getModule().openSetupGui(p);
            } else {
                p.sendMessage("§cKeine Rechte.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("result")) {
            if (args.length == 0) {
                p.sendMessage("§cNutze: /result <Spieler>");
                return true;
            }

            String targetName = args[0];
            plugin.getModule().openSpecificResult(p, targetName);
            return true;
        }

        if (command.getName().equalsIgnoreCase("reset")) {
            if (p.hasPermission("force.admin") || p.isOp()) {
                plugin.getModule().reset();
                Bukkit.broadcastMessage("§cForce Battle resettet!");
            } else {
                p.sendMessage("§cKeine Rechte.");
            }
            return true;
        }

        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("force")) {
            if (!sender.hasPermission("force.admin") && !sender.isOp()) return Collections.emptyList();
            if (args.length == 1) completions.add("skip");
            else if (args.length == 2 && args[0].equalsIgnoreCase("skip")) completions.add("give");
            else if (args.length == 3 && args[0].equalsIgnoreCase("skip") && args[1].equalsIgnoreCase("give")) return null;
        }

        if (command.getName().equalsIgnoreCase("result")) {
            if (args.length == 1) {
                return null;
            }
        }

        String currentArg = args[args.length - 1];
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(currentArg.toLowerCase()))
                .collect(Collectors.toList());
    }
}