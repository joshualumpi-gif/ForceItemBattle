package de.deinname.monsterarmy;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.example.events.TimerStartEvent;
import org.example.events.TimerFinishEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ForceListener implements Listener {

    private final Main plugin;
    private final Map<UUID, Integer> savedJokers = new HashMap<>();

    public ForceListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTimerStart(TimerStartEvent e) {
        Bukkit.getWorlds().forEach(w -> w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false));
        plugin.getModule().onTimerStart();
    }

    @EventHandler
    public void onTimerStop(TimerFinishEvent e) {
        plugin.getModule().onTimerStop();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getModule().onPlayerJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getModule().cleanupPlayer(e.getPlayer());
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();

        if (title.equals("§8Force Config") || title.equals("§8Force Probabilities") ||
                title.equals("§8Item Categories") || title.equals("§8Copper Categories") ||
                title.equals("§8End Categories") || title.equals("§8Coordinate Settings") ||
                title.startsWith("§8Settings:")) {

            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory())) {
                plugin.getModule().handleGuiClick(p, e.getSlot(), title, e.isLeftClick(), e.isShiftClick());
            }
            return;
        }

        Inventory currentResultInv = plugin.getModule().getState().currentResultInventory;
        if (currentResultInv != null && e.getView().getTopInventory().equals(currentResultInv)) {
            e.setCancelled(true);

            if (e.getClickedInventory() != null && e.getClickedInventory().equals(currentResultInv)) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked == null) return;

                if (clicked.getType() == Material.RED_STAINED_GLASS_PANE && e.getSlot() == ForceModule.SLOT_PREV) {
                    plugin.getModule().switchResultPage(p, -1);
                }
                else if (clicked.getType() == Material.LIME_STAINED_GLASS_PANE && e.getSlot() == ForceModule.SLOT_NEXT) {
                    plugin.getModule().switchResultPage(p, 1);
                }
            }
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        Inventory currentResultInv = plugin.getModule().getState().currentResultInventory;

        if (title.startsWith("§8Force")) {
            e.setCancelled(true);
        }
        else if (currentResultInv != null && e.getView().getTopInventory().equals(currentResultInv)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item != null && item.getType() == Material.BARRIER
                && item.hasItemMeta()
                && "§c§lSKIP".equals(item.getItemMeta().getDisplayName())) {

            if (e.getAction().name().contains("RIGHT")) {
                e.setCancelled(true);
                plugin.getModule().useJoker(e.getPlayer());
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Iterator<ItemStack> it = e.getDrops().iterator();
        int count = 0;

        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item != null && item.getType() == Material.BARRIER
                    && item.hasItemMeta()
                    && "§c§lSKIP".equals(item.getItemMeta().getDisplayName())) {

                count += item.getAmount();
                it.remove();
            }
        }

        if (count > 0) {
            savedJokers.put(e.getEntity().getUniqueId(), count);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (savedJokers.containsKey(p.getUniqueId())) {
            int amount = savedJokers.remove(p.getUniqueId());
            plugin.getModule().giveSkipItem(p, amount);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            plugin.getModule().manualCheck((Player) e.getEntity(), ForceModule.Category.DAMAGE, e.getFinalDamage());
        }
    }

    @EventHandler
    public void onAchievement(org.bukkit.event.player.PlayerAdvancementDoneEvent e) {
        if (e.getAdvancement().getKey().getKey().startsWith("recipes/")) return;

        Player p = e.getPlayer();
        String team = plugin.getModule().getTeam(p);
        ForceModule.Task t = plugin.getModule().getState().currentTasks.get(team);

        if (plugin.getModule().getState().activeCategories.contains(ForceModule.Category.ACHIEVEMENT)) {
            if (t != null && t.category == ForceModule.Category.ACHIEVEMENT && e.getAdvancement().getKey().equals(t.target)) {

                broadcastAdvancement(p, e.getAdvancement());

                plugin.getModule().manualCheck(p, ForceModule.Category.ACHIEVEMENT, e.getAdvancement().getKey());
            } else {
                org.bukkit.advancement.AdvancementProgress progress = p.getAdvancementProgress(e.getAdvancement());
                for (String criteria : progress.getAwardedCriteria()) {
                    progress.revokeCriteria(criteria);
                }
            }
        }
    }

    private void broadcastAdvancement(Player p, org.bukkit.advancement.Advancement adv) {
        if (adv.getDisplay() == null) return;

        net.kyori.adventure.text.format.NamedTextColor color;
        switch (adv.getDisplay().frame()) {
            case GOAL: color = net.kyori.adventure.text.format.NamedTextColor.AQUA; break;
            case CHALLENGE: color = net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE; break;
            default: color = net.kyori.adventure.text.format.NamedTextColor.GREEN; break;
        }

        net.kyori.adventure.text.Component advTitle = net.kyori.adventure.text.Component.text("[")
                .append(adv.getDisplay().title().color(color))
                .append(net.kyori.adventure.text.Component.text("]"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        adv.getDisplay().title().color(color)
                                .append(net.kyori.adventure.text.Component.newline())
                                .append(adv.getDisplay().description().color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                ));

        String frameType = adv.getDisplay().frame().name().toLowerCase();
        net.kyori.adventure.text.Component message = net.kyori.adventure.text.Component.translatable("chat.type.advancement." + frameType)
                .args(p.displayName(), advTitle);

        Bukkit.broadcast(message);
    }

    @EventHandler
    public void onMobKill(EntityDeathEvent e) {
        if (e.getEntity().getKiller() != null) {
            plugin.getModule().manualCheck(e.getEntity().getKiller(), ForceModule.Category.MOBS, e.getEntityType());
        }
    }
}