package de.deinname.monsterarmy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.boss.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.example.TimerPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class ForceModule {

    private final Main plugin;
    private final ForceGameState state;
    private final Random random = new Random();

    private final List<NamespacedKey> validAdvancements = new ArrayList<>();
    private final List<Material> validItems = new ArrayList<>();
    private final List<EntityType> validMobs = new ArrayList<>();

    private final int[] RESULT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private final int ITEMS_PER_PAGE = RESULT_SLOTS.length;

    public static final int SLOT_PREV = 27;
    public static final int SLOT_NEXT = 35;

    public enum Category {
        ITEMS(Material.DIAMOND_SWORD, "Collect Items"),
        MOBS(Material.SPAWNER, "Kill Mob"),
        ACHIEVEMENT(Material.BOOK, "Get Achievement"),
        BIOMES(Material.GRASS_BLOCK, "Find Biomes"),
        POSITION(Material.COMPASS, "Reach Coordinates"),
        HEIGHT(Material.LADDER, "Reach Height"),
        DAMAGE(Material.CACTUS, "Take Damage");

        final Material icon;
        final String name;
        Category(Material icon, String name) { this.icon = icon; this.name = name; }
    }

    public static class Task {
        public Category category;
        public Object target;
        public String displayName;
        public Task(Category c, Object t, String n) { category=c; target=t; displayName=n; }
    }

    public static class HistoryEntry {
        Task task;
        long timestamp;
        boolean usedJoker;
        public HistoryEntry(Task t, long time, boolean joker) { task=t; timestamp=time; usedJoker=joker; }
    }

    public ForceModule(Main plugin) {
        this.plugin = plugin;
        this.state = new ForceGameState();

        loadSettings();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            initAdvancements();
            initValidItems();
            initValidMobs();
        }, 20L);

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void disable() {
        state.teamBossBars.values().forEach(BossBar::removeAll);
    }

    private void tick() {
        if (!TimerPlugin.getInstance().isRunning()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.setPlayerListName(p.getName());
            }
            return;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            checkTask(p);
            updateTabList(p);
        }
    }

    private void updateTabList(Player p) {
        String team = getTeam(p);
        Task t = state.currentTasks.get(team);

        if (t != null) {
            String cleanTarget = "";

            if (t.target instanceof Material) {
                cleanTarget = formatName(((Material) t.target).name());
            } else if (t.target instanceof org.bukkit.block.Biome) {
                cleanTarget = formatName(((org.bukkit.block.Biome) t.target).getKey().getKey());
            } else if (t.target instanceof EntityType) {
                cleanTarget = formatName(((EntityType) t.target).name());
            } else if (t.category == Category.ACHIEVEMENT && t.target instanceof NamespacedKey) {
                Advancement adv = Bukkit.getAdvancement((NamespacedKey) t.target);
                cleanTarget = (adv != null && adv.getDisplay() != null)
                        ? PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title())
                        : ((NamespacedKey) t.target).getKey();
            } else if (t.category == Category.POSITION && t.target instanceof Location) {
                Location loc = (Location) t.target;
                cleanTarget = loc.getBlockX() + ", " + loc.getBlockZ();
            } else if (t.category == Category.HEIGHT) {
                cleanTarget = "Y=" + t.target;
            } else if (t.category == Category.DAMAGE) {
                double dmg = (double) t.target;
                cleanTarget = (dmg % 2 == 0 ? (int)(dmg / 2) : (dmg / 2.0)) + " Hearts";
            }

            p.setPlayerListName(p.getName() + " §7[§6" + cleanTarget + "§7]");
        } else {
            p.setPlayerListName(p.getName());
        }
    }

    String getTeam(Player p) { return p.getName(); }

    private List<Player> getTeamMembers(String teamName) {
        Player p = Bukkit.getPlayer(teamName);
        return (p != null && p.isOnline()) ? Collections.singletonList(p) : Collections.emptyList();
    }

    public void onTimerStart() {
        for (BossBar bar : state.teamBossBars.values()) bar.removeAll();
        state.teamBossBars.clear();

        boolean restored = tryRestoreGameState();

        if (!restored) {
            state.startTime = System.currentTimeMillis();
            state.gameHistory.clear();
            state.currentTasks.clear();
        }

        Set<String> activeTeams = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) activeTeams.add(getTeam(p));

        for (String teamName : activeTeams) {
            if (!state.teamBossBars.containsKey(teamName)) {
                Task current = state.currentTasks.get(teamName);
                String title = (current != null) ? current.displayName : "Loading...";
                BossBar bar = Bukkit.createBossBar(title, BarColor.WHITE, BarStyle.SOLID);
                state.teamBossBars.put(teamName, bar);
            }
            BossBar bar = state.teamBossBars.get(teamName);
            for (Player member : getTeamMembers(teamName)) bar.addPlayer(member);

            if (!restored) {
                state.gameHistory.put(teamName, new ArrayList<>());
                distributeJokerItems(teamName);
                nextTask(teamName);
            }
        }
    }

    public void onTimerStop() {
        saveGameState();
        state.rankedTeams = state.gameHistory.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        state.currentResultIndex = state.rankedTeams.size();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setGameMode(GameMode.ADVENTURE);
            p.setPlayerListName(p.getName());
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.clearTitle();
            String team = getTeam(p);
            if (team != null) {
                BossBar bar = state.teamBossBars.get(team);
                if (bar != null) bar.removePlayer(p);
            }
        }
        state.teamBossBars.clear();
    }

    public void reset() {
        if (TimerPlugin.getInstance().isRunning()) {
            TimerPlugin.getInstance().setRunning(false);
            TimerPlugin.getInstance().reset();
            TimerPlugin.getInstance().setCountUp(false);
            onTimerStop();
        }
        state.startTime = 0;
        state.gameHistory.clear();
        state.currentTasks.clear();
        state.teamBossBars.values().forEach(BossBar::removeAll);
        state.teamBossBars.clear();
        state.rankedTeams.clear();
        state.currentResultIndex = 0;
        state.currentResultInventory = null;

        plugin.getConfig().set("force", null);
        plugin.saveConfig();
    }

    public ForceGameState getState() { return state; }

    public void onPlayerJoin(Player p) {
        String team = getTeam(p);
        if (state.teamBossBars.containsKey(team)) state.teamBossBars.get(team).addPlayer(p);
    }

    public void cleanupPlayer(Player p) {
        for (BossBar bar : state.teamBossBars.values()) bar.removePlayer(p);
    }

    private void distributeJokerItems(String teamName) {
        List<Player> members = getTeamMembers(teamName);
        if (members.isEmpty()) return;
        int totalJokers = state.jokerCount;
        for (Player p : members) if (totalJokers > 0) giveSkipItem(p, totalJokers);
    }

    public void giveSkipItem(Player p, int amount) {
        ItemStack b = new ItemStack(Material.BARRIER, amount);
        ItemMeta m = b.getItemMeta();
        m.setDisplayName("§c§lSKIP");
        b.setItemMeta(m);
        p.getInventory().addItem(b);
    }

    public void nextTask(String teamName) {
        List<Category> cats = new ArrayList<>(state.activeCategories);
        Category cat = getWeightedRandomCategory(cats);

        Task task = generateTask(teamName, cat);
        state.currentTasks.put(teamName, task);

        BossBar bar = state.teamBossBars.get(teamName);
        if (bar != null) {
            bar.setTitle(task.displayName);
            bar.setVisible(true);
        }
        for (Player member : getTeamMembers(teamName)) {
            member.playSound(member.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }
        saveGameState();
    }

    public void checkTask(Player p) {
        String team = getTeam(p);
        if (team == null) return;
        Task t = state.currentTasks.get(team);
        if (t == null) return;
        boolean done = false;
        if (t.category == Category.ITEMS) { if (p.getInventory().contains((Material) t.target)) done = true; }
        else if (t.category == Category.BIOMES) { if (p.getLocation().getBlock().getBiome() == (org.bukkit.block.Biome) t.target) done = true; }
        else if (t.category == Category.POSITION) {
            Location ta = (Location) t.target;
            if (ta != null && ta.getWorld() == p.getWorld()) {
                int playerX = (int) p.getLocation().getX();
                int playerZ = (int) p.getLocation().getZ();
                if (playerX == ta.getBlockX() && playerZ == ta.getBlockZ()) {
                    done = true;
                }
            }
        }
        else if (t.category == Category.HEIGHT) { if (Math.abs(p.getLocation().getBlockY()-(int)t.target)<3) done = true; }
        if (done) completeTask(team, p);
    }

    public void manualCheck(Player p, Category cat, Object value) {
        String team = getTeam(p);
        if (team == null) return;
        Task t = state.currentTasks.get(team);
        if (t != null && t.category == cat) {
            boolean done = false;
            if (cat == Category.DAMAGE && Math.abs((double)value - (double)t.target) < 0.1) done = true;
            else if (cat == Category.ACHIEVEMENT && ((NamespacedKey)value).equals(t.target)) done = true;
            else if (cat == Category.MOBS && ((EntityType)value) == t.target) done = true;
            if (done) completeTask(team, p);
        }
    }

    private void completeTask(String teamName, Player solver) {
        logHistory(teamName, false);
        for (Player member : getTeamMembers(teamName)) {
            member.playSound(member.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        }
        nextTask(teamName);
    }

    public void useJoker(Player p) {
        String team = getTeam(p);
        if (team == null) return;
        Task t = state.currentTasks.get(team);
        if (state.giveItemOnSkip && t != null && t.category == Category.ITEMS && t.target instanceof Material) {
            p.getInventory().addItem(new ItemStack((Material) t.target));
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.BARRIER) {
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(hand);
            p.updateInventory();
        }
        logHistory(team, true);
        for (Player member : getTeamMembers(team)) {
            member.playSound(member.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        }
        nextTask(team);
    }

    private void logHistory(String teamName, boolean joker) {
        Task t = state.currentTasks.get(teamName);
        if (t == null) return;
        long timerValue = TimerPlugin.getInstance().getTime();
        state.gameHistory.computeIfAbsent(teamName, k -> new ArrayList<>()).add(new HistoryEntry(t, timerValue, joker));
    }

    public void saveGameState() {
        FileConfiguration data = plugin.getConfig();
        data.set("force.startTime", state.startTime);
        data.set("force.teams", null);
        for (Map.Entry<String, List<HistoryEntry>> entry : state.gameHistory.entrySet()) {
            String team = entry.getKey();
            String path = "force.teams." + team;
            Task current = state.currentTasks.get(team);
            if (current != null) saveTaskToConfig(data, path + ".current", current);
            List<HistoryEntry> hist = entry.getValue();
            for (int i=0; i<hist.size(); i++) {
                HistoryEntry h = hist.get(i);
                String hPath = path + ".history." + i;
                saveTaskToConfig(data, hPath, h.task);
                data.set(hPath + ".time", h.timestamp);
                data.set(hPath + ".joker", h.usedJoker);
            }
        }
        plugin.saveConfig();
    }

    public boolean tryRestoreGameState() {
        FileConfiguration data = plugin.getConfig();
        if (!data.contains("force.startTime")) return false;
        state.startTime = data.getLong("force.startTime");
        state.gameHistory.clear();
        state.currentTasks.clear();
        state.teamBossBars.clear();
        if (data.getConfigurationSection("force.teams") == null) return false;
        for (String team : data.getConfigurationSection("force.teams").getKeys(false)) {
            String path = "force.teams." + team;
            if (data.contains(path + ".current.cat")) {
                Task t = loadTaskFromConfig(data, path + ".current");
                if (t != null) state.currentTasks.put(team, t);
            }
            List<HistoryEntry> history = new ArrayList<>();
            if (data.contains(path + ".history")) {
                for (String key : data.getConfigurationSection(path + ".history").getKeys(false)) {
                    String hPath = path + ".history." + key;
                    Task t = loadTaskFromConfig(data, hPath);
                    if (t != null) history.add(new HistoryEntry(t, data.getLong(hPath + ".time"), data.getBoolean(hPath + ".joker")));
                }
            }
            state.gameHistory.put(team, history);
        }
        return true;
    }

    private void saveTaskToConfig(FileConfiguration data, String path, Task task) {
        data.set(path + ".cat", task.category.name());
        data.set(path + ".name", task.displayName);
        if (task.target instanceof Material) data.set(path + ".targetType", "MATERIAL:" + ((Material)task.target).name());
        else if (task.target instanceof org.bukkit.block.Biome) data.set(path + ".targetType", "BIOME:" + ((org.bukkit.block.Biome)task.target).name());
        else if (task.target instanceof Integer) data.set(path + ".targetType", "INT:" + task.target);
        else if (task.target instanceof Double) data.set(path + ".targetType", "DOUBLE:" + task.target);
        else if (task.target instanceof Location) {
            Location loc = (Location) task.target;
            data.set(path + ".targetType", "LOC:" + loc.getBlockX() + "," + loc.getBlockZ());
        }
        else if (task.target instanceof NamespacedKey) data.set(path + ".targetType", "KEY:" + ((NamespacedKey)task.target).toString());
        else if (task.target instanceof EntityType) data.set(path + ".targetType", "MOB:" + ((EntityType)task.target).name());
    }

    private Task loadTaskFromConfig(FileConfiguration data, String path) {
        try {
            String catName = data.getString(path + ".cat");
            String dispName = data.getString(path + ".name");
            String targetRaw = data.getString(path + ".targetType");
            Category cat = Category.valueOf(catName);
            Object target = null;
            if (targetRaw != null) {
                String[] parts = targetRaw.split(":", 2);
                String type = parts[0];
                String val = parts[1];
                switch (type) {
                    case "MATERIAL": target = Material.valueOf(val); break;
                    case "BIOME": target = Registry.BIOME.get(NamespacedKey.minecraft(val.toLowerCase())); break;
                    case "INT": target = Integer.parseInt(val); break;
                    case "DOUBLE": target = Double.parseDouble(val); break;
                    case "KEY": target = NamespacedKey.fromString(val); break;
                    case "MOB": target = EntityType.valueOf(val); break;
                    case "LOC":
                        String[] coords = val.split(",");
                        target = new Location(Bukkit.getWorlds().get(0), Integer.parseInt(coords[0]), 0, Integer.parseInt(coords[1]));
                        break;
                }
            }
            return new Task(cat, target, dispName);
        } catch (Exception e) { return null; }
    }

    public void openSpecificResult(Player p, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        String lookupName = (target != null) ? target.getName() : targetName;

        if (!state.gameHistory.containsKey(lookupName)) {
            p.sendMessage("§cKeine Daten für Spieler " + lookupName + " gefunden.");
            return;
        }

        if (lookupName.equals(state.currentViewingTeam) && state.currentResultInventory != null) {
            p.openInventory(state.currentResultInventory);
            return;
        }

        int rank = state.rankedTeams.indexOf(lookupName) + 1;
        boolean isFinished = state.finishedAnimations.contains(lookupName);
        createResultInventory(lookupName, rank, 0, !isFinished);

        List<HistoryEntry> history = state.gameHistory.getOrDefault(lookupName, new ArrayList<>());
        int points = history.size();

        Component message = Component.text(lookupName, NamedTextColor.GOLD)
                .append(Component.text(":", NamedTextColor.GRAY))
                .append(Component.text(points, NamedTextColor.AQUA))
                .append(Component.text(":", NamedTextColor.GRAY))
                .append(Component.text("MENU", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/result " + lookupName))
                        .hoverEvent(HoverEvent.showText(Component.text("Ergebnis öffnen"))));

        if (!isFinished) {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                onlinePlayer.openInventory(state.currentResultInventory);
                onlinePlayer.sendMessage(message);
            }
        } else {
            p.openInventory(state.currentResultInventory);
            p.sendMessage(message);
        }
    }

    public void switchResultPage(Player p, int pageDelta) {
        if (state.currentViewingTeam == null) return;
        int newPage = state.currentViewingPage + pageDelta;
        if (newPage < 0) return;

        List<HistoryEntry> history = state.gameHistory.getOrDefault(state.currentViewingTeam, new ArrayList<>());
        if (newPage * ITEMS_PER_PAGE >= history.size() && newPage > 0) return;

        createResultInventory(state.currentViewingTeam, state.currentResultIndex + 1, newPage, false);
        p.openInventory(state.currentResultInventory);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    private void createResultInventory(String teamName, int rank, int page, boolean animate) {
        state.currentViewingTeam = teamName;
        state.currentViewingPage = page;

        state.currentResultInventory = Bukkit.createInventory(null, 54, "§8" + teamName);
        ItemStack whiteGlass = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        ItemMeta wMeta = whiteGlass.getItemMeta(); wMeta.setDisplayName("§7"); whiteGlass.setItemMeta(wMeta);
        ItemStack grayGlass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gMeta = grayGlass.getItemMeta(); gMeta.setDisplayName("§7"); grayGlass.setItemMeta(gMeta);

        for(int i=0; i<54; i++) {
            if (i < 9) state.currentResultInventory.setItem(i, whiteGlass);
            else state.currentResultInventory.setItem(i, grayGlass);
        }

        List<HistoryEntry> history = state.gameHistory.getOrDefault(teamName, new ArrayList<>());

        if (state.animationTask != null && !state.animationTask.isCancelled()) {
            state.animationTask.cancel();
        }

        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = animate ? history.size() : Math.min(startIndex + ITEMS_PER_PAGE, history.size());

        Runnable updateButtons = () -> {
            int currentPage = state.currentViewingPage;
            state.currentResultInventory.setItem(SLOT_PREV, grayGlass);
            state.currentResultInventory.setItem(SLOT_NEXT, grayGlass);

            if (currentPage > 0) {
                ItemStack prev = new ItemStack(Material.RED_STAINED_GLASS_PANE);
                ItemMeta pm = prev.getItemMeta(); pm.setDisplayName("§cZurück"); prev.setItemMeta(pm);
                state.currentResultInventory.setItem(SLOT_PREV, prev);
            }
            if ((currentPage + 1) * ITEMS_PER_PAGE < history.size()) {
                ItemStack next = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                ItemMeta nm = next.getItemMeta(); nm.setDisplayName("§aVorwärts"); next.setItemMeta(nm);
                state.currentResultInventory.setItem(SLOT_NEXT, next);
            }
        };

        if (!animate) {
            for (int i = startIndex; i < endIndex; i++) {
                setItemAt(i, startIndex, history.get(i));
            }
            updateButtons.run();
        } else {
            state.animationTask = new BukkitRunnable() {
                int currentI = startIndex;

                @Override
                public void run() {
                    if (currentI >= endIndex) {
                        state.finishedAnimations.add(teamName);
                        updateButtons.run();
                        this.cancel();
                        return;
                    }

                    int pageOfItem = currentI / ITEMS_PER_PAGE;

                    if (pageOfItem > state.currentViewingPage) {
                        state.currentViewingPage = pageOfItem;
                        for (int slot : RESULT_SLOTS) {
                            state.currentResultInventory.setItem(slot, new ItemStack(Material.AIR));
                        }
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.getOpenInventory().getTopInventory().equals(state.currentResultInventory)) {
                                p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
                            }
                        }
                    }

                    setItemAt(currentI, state.currentViewingPage * ITEMS_PER_PAGE, history.get(currentI));

                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getOpenInventory().getTopInventory().equals(state.currentResultInventory)) {
                            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 2f);
                        }
                    }
                    currentI++;
                }
            }.runTaskTimer(plugin, 0L, 15L);
        }
    }

    private void setItemAt(int absoluteIndex, int pageStartIndex, HistoryEntry entry) {
        int relativeIndex = absoluteIndex - pageStartIndex;
        if (relativeIndex >= RESULT_SLOTS.length || relativeIndex < 0) return;
        int slot = RESULT_SLOTS[relativeIndex];

        Material icon = Material.PAPER;
        switch (entry.task.category) {
            case HEIGHT: case POSITION: case BIOMES: icon = Material.LEATHER_BOOTS; break;
            case DAMAGE: icon = Material.POTION; break;
            case ACHIEVEMENT: icon = Material.WRITTEN_BOOK; break;
            case ITEMS: if (entry.task.target instanceof Material) icon = (Material) entry.task.target; break;
            case MOBS:
                if (entry.task.target instanceof EntityType) icon = getSpawnEgg((EntityType) entry.task.target);
                else icon = Material.ZOMBIE_HEAD;
                break;
        }

        ItemStack resultItem = new ItemStack(icon);
        ItemMeta meta = resultItem.getItemMeta();
        meta.setDisplayName("§f" + entry.task.displayName);
        List<String> lore = new ArrayList<>();
        lore.add("§6" + formatShortTime(entry.timestamp));
        if (entry.usedJoker) lore.add("§c[Joker]");
        meta.setLore(lore);
        resultItem.setItemMeta(meta);

        state.currentResultInventory.setItem(slot, resultItem);
    }

    public void reopenResult(Player p) {
        if (state.currentResultInventory != null) p.openInventory(state.currentResultInventory);
    }

    public void openSetupGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8Force Config");
        fillGui(inv);

        int slot = 10;
        for (Category cat : Category.values()) {
            boolean active = state.activeCategories.contains(cat);
            ItemStack item = new ItemStack(cat.icon);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName((active ? "§a✔ " : "§c✖ ") + cat.name);

            item.setItemMeta(meta);
            inv.setItem(slot, item);

            if (active && cat != Category.DAMAGE && cat != Category.HEIGHT) {
                ItemStack settings = new ItemStack(Material.REPEATER);
                ItemMeta sMeta = settings.getItemMeta();
                sMeta.setDisplayName("§eSettings: " + cat.name);
                settings.setItemMeta(sMeta);
                inv.setItem(slot - 9, settings);
            }
            slot++;
        }

        inv.setItem(22, createNavButton(Material.BARRIER, "§c" + state.jokerCount + " Jokers"));
        inv.setItem(24, createNavButton(state.allowDuplicates ? Material.REPEATER : Material.COMPARATOR,
                (state.allowDuplicates ? "§a✔ Duplikate erlaubt" : "§c✖ Duplikate verboten")));

        if (state.activeCategories.contains(Category.ITEMS)) {
            ItemStack skipItem = new ItemStack(state.giveItemOnSkip ? Material.CHEST : Material.MINECART);
            ItemMeta skipMeta = skipItem.getItemMeta();
            skipMeta.setDisplayName(state.giveItemOnSkip ? "§a✔ Item bei Skip geben" : "§c✖ Kein Item bei Skip");
            skipItem.setItemMeta(skipMeta);
            inv.setItem(25, skipItem);
        }

        inv.setItem(26, createNavButton(Material.ARROW, "§eWahrscheinlichkeiten"));

        p.openInventory(inv);
    }

    public void openProbabilityGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8Force Probabilities");
        fillGui(inv);
        List<Category> activeList = new ArrayList<>(state.activeCategories);
        int size = activeList.size();
        int totalWeight = 0;
        for (Category c : activeList) totalWeight += state.categoryWeights.getOrDefault(c, 10);
        int offset = (9 - size) / 2;
        int startSlot = 9 + offset;
        for (int i = 0; i < size; i++) {
            Category cat = activeList.get(i);
            int weight = state.categoryWeights.getOrDefault(cat, 10);
            ItemStack item = new ItemStack(cat.icon);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§e" + cat.name);
            List<String> lore = new ArrayList<>();
            double percent = totalWeight > 0 ? ((double)weight / totalWeight) * 100 : 0;
            lore.add("§7Gewichtung: §6" + weight);
            lore.add(String.format("§7Chance: §b%.1f%%", percent));
            lore.add("§7(Links: +1, Rechts: -1)");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(startSlot + i, item);
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bMeta = back.getItemMeta(); bMeta.setDisplayName("§cZurück"); back.setItemMeta(bMeta);
        inv.setItem(18, back);

        if (state.allowDuplicates) {
            ItemStack chanceItem = new ItemStack(Material.REPEATER);
            ItemMeta cMeta = chanceItem.getItemMeta();
            cMeta.setDisplayName("§eDuplikat-Wahrscheinlichkeit");
            cMeta.setLore(Arrays.asList(
                    "§7Aktuell: §6" + state.duplicateChance + "%",
                    "§7(Links: +1%, Rechts: -1%)",
                    "§7(Shift+Links: +10%, Shift+Rechts: -10%)",
                    "§7Range: 1% - 50%"
            ));
            chanceItem.setItemMeta(cMeta);
            inv.setItem(26, chanceItem);
        }
        p.openInventory(inv);
    }

    public void handleGuiClick(Player p, int slot, String title, boolean isLeft, boolean isShift) {
        if (title.equals("§8Force Config")) {
            if (slot >= 1 && slot <= 7) {
                int index = slot - 1;
                if (index < Category.values().length) {
                    Category cat = Category.values()[index];
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    if (cat == Category.ITEMS) openItemCategoryMenu(p);
                    else if (cat == Category.POSITION) openCoordSettings(p);
                    else openSubMenu(p, cat, 0, null);
                }
                return;
            }

            if (slot >= 10 && slot <= 16) {
                int index = slot - 10;
                if (index < Category.values().length) {
                    Category c = Category.values()[index];
                    if (state.activeCategories.contains(c)) state.activeCategories.remove(c);
                    else state.activeCategories.add(c);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                    openSetupGui(p);
                }
            }
            if (slot == 22) {
                if (isLeft) state.jokerCount++;
                else if (state.jokerCount > 0) state.jokerCount--;
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                refreshAllGuis();
            }
            else if (slot == 24) {
                state.allowDuplicates = !state.allowDuplicates;
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                refreshAllGuis();
            }
            else if (slot == 25 && state.activeCategories.contains(Category.ITEMS)) {
                state.giveItemOnSkip = !state.giveItemOnSkip;
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                refreshAllGuis();
            }
            else if (slot == 26) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openProbabilityGui(p);
            }
        }

        else if (title.equals("§8Item Categories")) {
            if (slot == 22) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openSetupGui(p);
                return;
            }

            ItemStack clicked = p.getOpenInventory().getItem(slot);
            if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;
            String subName = formatNameFromIcon(clicked);

            if (subName.equalsIgnoreCase("Enditem")) {
                if (isShift) {
                    List<Object> combined = new ArrayList<>();
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "End Items (General)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Shulkerboxes"));

                    Set<Object> disabled = state.disabledTargets.computeIfAbsent(Category.ITEMS, k -> new HashSet<>());
                    boolean anyEnabled = false;
                    for (Object o : combined) {
                        if (!disabled.contains(o)) { anyEnabled = true; break; }
                    }
                    if (anyEnabled) disabled.addAll(combined);
                    else disabled.removeAll(combined);

                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
                    refreshAllGuis();
                } else {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    openEndCategoryMenu(p);
                }
            }
            else if (subName.equalsIgnoreCase("Copper")) {
                if (isShift) {
                    List<Object> combined = new ArrayList<>();
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Copper (Normal)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Copper (Exposed)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Copper (Weathered)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Copper (Oxidized)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Waxed Copper (Normal)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Waxed Copper (Exposed)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Waxed Copper (Weathered)"));
                    combined.addAll(getTargetsForCategory(Category.ITEMS, "Waxed Copper (Oxidized)"));

                    Set<Object> disabled = state.disabledTargets.computeIfAbsent(Category.ITEMS, k -> new HashSet<>());
                    boolean anyEnabled = false;
                    for (Object o : combined) {
                        if (!disabled.contains(o)) { anyEnabled = true; break; }
                    }
                    if (anyEnabled) disabled.addAll(combined);
                    else disabled.removeAll(combined);

                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
                    refreshAllGuis();
                } else {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    openCopperCategoryMenu(p);
                }
            }
            else {
                if (isShift) {
                    bulkToggle(p, Category.ITEMS, subName);
                    refreshAllGuis();
                } else {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    openSubMenu(p, Category.ITEMS, 0, subName);
                }
            }
        }

        else if (title.equals("§8End Categories") || title.equals("§8Copper Categories")) {
            int backSlot = title.equals("§8End Categories") ? 22 : 31;
            if (slot == backSlot) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openItemCategoryMenu(p);
                return;
            }

            ItemStack clicked = p.getOpenInventory().getItem(slot);
            if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;
            String subName = formatNameFromIcon(clicked);

            if (isShift) {
                bulkToggle(p, Category.ITEMS, subName);
                refreshAllGuis();
            } else {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openSubMenu(p, Category.ITEMS, 0, subName);
            }
        }

        else if (title.equals("§8Coordinate Settings")) {
            if (slot == 22) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openSetupGui(p);
            }
            else if (slot == 13) {
                if (isShift) {
                    state.maxRadius += (isLeft ? 100 : -100);
                    if (state.maxRadius < state.minRadius + 50) state.maxRadius = state.minRadius + 50;
                } else {
                    state.minRadius += (isLeft ? 50 : -50);
                    if (state.minRadius < 50) state.minRadius = 50;
                    if (state.minRadius > state.maxRadius - 50) state.minRadius = state.maxRadius - 50;
                }
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                openCoordSettings(p);
            }
        }

        else if (title.startsWith("§8Settings:")) {
            Category cat = getCategoryFromTitle(title);

            if (slot == 46) {
                bulkToggle(p, cat, state.currentItemSubCategory);
                openSubMenu(p, cat, state.currentViewingPage, state.currentItemSubCategory);
                return;
            }

            if (slot == 49) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                String sub = state.currentItemSubCategory;
                if (sub == null) openSetupGui(p);
                else if (sub.contains("Copper") || sub.contains("Waxed Copper")) openCopperCategoryMenu(p);
                else if (sub.equals("End Items (General)") || sub.equals("Shulkerboxes")) openEndCategoryMenu(p);
                else openItemCategoryMenu(p);
                return;
            }

            if (slot == 48) {
                if (state.currentViewingPage > 0) {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    openSubMenu(p, cat, state.currentViewingPage - 1, state.currentItemSubCategory);
                }
                return;
            }
            if (slot == 50) {
                List<Object> all = getTargetsForCategory(cat, state.currentItemSubCategory);
                if ((state.currentViewingPage + 1) * 28 < all.size()) {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    openSubMenu(p, cat, state.currentViewingPage + 1, state.currentItemSubCategory);
                }
                return;
            }

            if (isResultSlot(slot)) {
                ItemStack clicked = p.getOpenInventory().getItem(slot);
                if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

                List<Object> allTargets = getTargetsForCategory(cat, state.currentItemSubCategory);
                int index = (state.currentViewingPage * 28) + getResultIndex(slot);

                if (index < allTargets.size()) {
                    Object target = allTargets.get(index);
                    Set<Object> disabled = state.disabledTargets.computeIfAbsent(cat, k -> new HashSet<>());
                    if (disabled.contains(target)) disabled.remove(target);
                    else disabled.add(target);

                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                    openSubMenu(p, cat, state.currentViewingPage, state.currentItemSubCategory);
                }
            }
        }

        else if (slot == 26 && state.activeCategories.contains(Category.ITEMS)) {
            state.giveItemOnSkip = !state.giveItemOnSkip;
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
            refreshAllGuis();
        }

        else if (title.equals("§8Force Probabilities")) {
            if (slot == 18) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openSetupGui(p);
                return;
            }

            if (slot == 26 && state.allowDuplicates) {
                int delta = isLeft ? (isShift ? 10 : 1) : (isShift ? -10 : -1);
                state.duplicateChance = Math.max(1, Math.min(50, state.duplicateChance + delta));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                refreshAllGuis();
                return;
            }

            List<Category> activeList = new ArrayList<>(state.activeCategories);
            int offset = (9 - activeList.size()) / 2;
            int startSlot = 9 + offset;
            int clickedIndex = slot - startSlot;

            if (clickedIndex >= 0 && clickedIndex < activeList.size()) {
                Category c = activeList.get(clickedIndex);
                int weight = state.categoryWeights.getOrDefault(c, 10);
                if (isLeft) weight++;
                else if (weight > 1) weight--;
                state.categoryWeights.put(c, weight);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
                refreshAllGuis();
            }
        }

        saveSettings();
    }

    private String formatNameFromIcon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "";
        String name = item.getItemMeta().getDisplayName();
        name = name.replaceAll("§[0-9a-fk-orx]", "");
        name = name.replace("✔", "").replace("~", "").replace("✖", "");
        return name.trim();
    }

    private void bulkToggle(Player p, Category cat, String subName) {
        List<Object> allInSub = getTargetsForCategory(cat, subName);
        Set<Object> disabled = state.disabledTargets.computeIfAbsent(cat, k -> new HashSet<>());

        boolean anyEnabled = false;
        for (Object o : allInSub) {
            if (!disabled.contains(o)) {
                anyEnabled = true;
                break;
            }
        }

        if (anyEnabled) {
            disabled.addAll(allInSub);
        } else {
            disabled.removeAll(allInSub);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
    }

    private Category getCategoryFromTitle(String title) {
        for (Category cat : Category.values()) {
            if (title.contains(cat.name)) return cat;
        }
        return Category.ITEMS;
    }

    private boolean isResultSlot(int slot) {
        for (int s : RESULT_SLOTS) if (s == slot) return true;
        return false;
    }

    private int getResultIndex(int slot) {
        for (int i = 0; i < RESULT_SLOTS.length; i++) {
            if (RESULT_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    private void fillGui(Inventory inv) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta(); meta.setDisplayName("§7"); glass.setItemMeta(meta);
        for(int i=0; i<inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
    }

    private void initAdvancements() {
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (!adv.getKey().getKey().startsWith("recipes/") && adv.getDisplay() != null) validAdvancements.add(adv.getKey());
        }
    }

    private void initValidItems() {
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir()) continue;
            String name = m.name();
            if (name.contains("COMMAND_BLOCK") || name.contains("STRUCTURE_") || name.contains("JIGSAW") ||
                    name.equals("BARRIER") || name.equals("LIGHT") || name.equals("DEBUG_STICK") ||
                    name.equals("BEDROCK") || name.equals("END_PORTAL_FRAME") || name.equals("REINFORCED_DEEPSLATE") ||
                    name.equals("KNOWLEDGE_BOOK") || name.contains("TRIAL_SPAWNER") || name.contains("VAULT") ||
                    name.startsWith("INFESTED_") || name.contains("SPAWN_EGG") || name.contains("TEST_") ||
                    name.equals("VOID_AIR") || name.equals("CAVE_AIR")) {
                continue;
            }
            validItems.add(m);
        }
    }

    private void initValidMobs() {
        for (EntityType type : EntityType.values()) {
            if (type.isAlive() && type.isSpawnable() && type != EntityType.PLAYER && type != EntityType.ARMOR_STAND) {
                validMobs.add(type);
            }
        }
    }

    private Category getWeightedRandomCategory(List<Category> cats) {
        if (cats.isEmpty()) return Category.ITEMS;
        int totalWeight = 0;
        for (Category c : cats) totalWeight += state.categoryWeights.getOrDefault(c, 10);
        int r = random.nextInt(totalWeight);
        int current = 0;
        for (Category c : cats) {
            current += state.categoryWeights.getOrDefault(c, 10);
            if (r < current) return c;
        }
        return cats.get(0);
    }

    private Task generateTask(String playerName, Category c) {
        Player p = (playerName != null) ? Bukkit.getPlayer(playerName) : null;
        Location loc = (p != null) ? p.getLocation() : new Location(Bukkit.getWorlds().get(0), 0,0,0);

        List<Object> usedTargets = getUsedTargets(playerName, c);
        boolean forceDuplicate = state.allowDuplicates && !usedTargets.isEmpty() && random.nextInt(100) < state.duplicateChance;

        switch (c) {
            case ITEMS:
                List<Material> itemPool = validItems.stream()
                        .filter(m -> !state.disabledTargets.getOrDefault(Category.ITEMS, new HashSet<>()).contains(m))
                        .collect(Collectors.toList());

                if (forceDuplicate) {
                    List<Material> usedItems = usedTargets.stream().filter(o -> o instanceof Material).map(o -> (Material)o).collect(Collectors.toList());
                    if (!usedItems.isEmpty()) {
                        Material mat = usedItems.get(random.nextInt(usedItems.size()));
                        return new Task(c, mat, "§6Item: §f" + formatName(mat.name()));
                    }
                }

                itemPool.removeAll(usedTargets);
                if (itemPool.isEmpty()) itemPool.addAll(validItems);

                Material mat = itemPool.get(random.nextInt(itemPool.size()));
                return new Task(c, mat, "§6Item: §f" + formatName(mat.name()));

            case BIOMES:
                List<org.bukkit.block.Biome> biomePool = new ArrayList<>();
                org.bukkit.Registry.BIOME.forEach(biomePool::add);

                if (forceDuplicate) {
                    List<org.bukkit.block.Biome> usedBiomes = usedTargets.stream().filter(o -> o instanceof org.bukkit.block.Biome).map(o -> (org.bukkit.block.Biome)o).collect(Collectors.toList());
                    if (!usedBiomes.isEmpty()) {
                        org.bukkit.block.Biome b = usedBiomes.get(random.nextInt(usedBiomes.size()));
                        return new Task(c, b, "§2Biome: §f" + formatName(b.getKey().getKey()));
                    }
                }
                biomePool.removeAll(usedTargets);
                if (biomePool.isEmpty()) org.bukkit.Registry.BIOME.forEach(biomePool::add);

                org.bukkit.block.Biome b = biomePool.get(random.nextInt(biomePool.size()));
                return new Task(c, b, "§2Biome: §f" + formatName(b.getKey().getKey()));

            case POSITION:
                int radius = state.minRadius + random.nextInt(state.maxRadius - state.minRadius + 1);
                double angle = random.nextDouble() * 2 * Math.PI;
                int x = loc.getBlockX() + (int) (Math.cos(angle) * radius);
                int z = loc.getBlockZ() + (int) (Math.sin(angle) * radius);
                return new Task(c, new Location(loc.getWorld(), x, 0, z), "§bGo to: §fX=" + x + ", Z=" + z);

            case HEIGHT:
                int y = random.nextInt(150) - 40;
                return new Task(c, y, "§bReach Height: §fY=" + y);

            case DAMAGE:
                int dmg = random.nextInt(19) + 1;
                return new Task(c, (double)dmg, "§cTake " + (dmg%2==0 ? dmg/2 : dmg/2.0) + " Hearts of damage");

            case ACHIEVEMENT:
                if (validAdvancements.isEmpty()) initAdvancements();
                List<NamespacedKey> advPool = new ArrayList<>(validAdvancements);

                if (forceDuplicate) {
                    List<NamespacedKey> usedAdvs = usedTargets.stream().filter(o -> o instanceof NamespacedKey).map(o -> (NamespacedKey)o).collect(Collectors.toList());
                    if (!usedAdvs.isEmpty()) {
                        NamespacedKey key = usedAdvs.get(random.nextInt(usedAdvs.size()));
                        Advancement adv = Bukkit.getAdvancement(key);
                        String title = (adv!=null && adv.getDisplay()!=null) ? PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title()) : key.getKey();
                        return new Task(c, key, "§eAchievement: §f" + title);
                    }
                }
                advPool.removeAll(usedTargets);
                if (advPool.isEmpty()) advPool.addAll(validAdvancements);

                NamespacedKey key = advPool.get(random.nextInt(advPool.size()));
                Advancement adv = Bukkit.getAdvancement(key);
                String title = (adv!=null && adv.getDisplay()!=null) ? PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title()) : key.getKey();
                return new Task(c, key, "§eAchievement: §f" + title);

            case MOBS:
                if (validMobs.isEmpty()) initValidMobs();
                List<EntityType> mobPool = new ArrayList<>(validMobs);

                if (forceDuplicate) {
                    List<EntityType> usedMobs = usedTargets.stream().filter(o -> o instanceof EntityType).map(o -> (EntityType)o).collect(Collectors.toList());
                    if (!usedMobs.isEmpty()) {
                        EntityType type = usedMobs.get(random.nextInt(usedMobs.size()));
                        return new Task(c, type, "§cKill: §f" + formatName(type.name()));
                    }
                }

                mobPool.removeAll(usedTargets);
                if (!usedTargets.contains(EntityType.PLAYER)) {
                    mobPool.add(EntityType.PLAYER);
                }

                if (mobPool.isEmpty()) mobPool.addAll(validMobs);

                EntityType type = mobPool.get(random.nextInt(mobPool.size()));
                return new Task(c, type, "§cKill: §f" + formatName(type.name()));

            default: return new Task(c, null, "Err");
        }
    }

    private String formatName(String s) {
        if (s == null || s.isEmpty()) return "";
        String formatted = s.toLowerCase().replace("_", " ");
        String[] words = formatted.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String formatShortTime(long totalSeconds) {
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    private Material getSpawnEgg(EntityType type) {
        if (type == null) return Material.BARRIER;
        Material mat = Material.matchMaterial(type.name() + "_SPAWN_EGG");
        if (type == EntityType.PLAYER) return Material.PLAYER_HEAD;
        if (mat != null) return mat;
        return Material.GHAST_TEAR;
    }

    private List<Object> getUsedTargets(String playerName, Category c) {
        List<Object> used = new ArrayList<>();
        if (playerName == null) return used;
        Task current = state.currentTasks.get(playerName);
        if (current != null && current.category == c) used.add(current.target);
        for (HistoryEntry e : state.gameHistory.getOrDefault(playerName, new ArrayList<>())) {
            if (e.task.category == c) used.add(e.task.target);
        }
        return used;
    }

    public void openSubMenu(Player p, Category cat, int page, String subCat) {
        state.currentViewingPage = page;
        state.currentItemSubCategory = subCat;

        String title = "§8Settings: " + (subCat != null ? subCat : cat.name);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        fillGui(inv);

        List<Object> allTargets = getTargetsForCategory(cat, subCat);

        int start = page * 28;
        for (int i = 0; i < 28 && (start + i) < allTargets.size(); i++) {
            Object target = allTargets.get(start + i);
            boolean disabled = state.disabledTargets.getOrDefault(cat, new HashSet<>()).contains(target);

            ItemStack icon = getIconForTarget(cat, target);
            ItemMeta meta = icon.getItemMeta();

            String displayName = "Unknown";
            if (cat == Category.ACHIEVEMENT && target instanceof NamespacedKey) {
                Advancement adv = Bukkit.getAdvancement((NamespacedKey) target);
                displayName = (adv != null && adv.getDisplay() != null)
                        ? PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title())
                        : ((NamespacedKey) target).getKey();
            } else if (cat == Category.BIOMES && target instanceof org.bukkit.block.Biome) {
                displayName = formatName(((org.bukkit.block.Biome) target).getKey().getKey());
            } else {
                displayName = formatName(target.toString());
            }

            meta.setDisplayName((disabled ? "§c✖ " : "§a✔ ") + "§f" + displayName);
            icon.setItemMeta(meta);
            inv.setItem(RESULT_SLOTS[i], icon);
        }

        inv.setItem(49, createNavButton(Material.BARRIER, "§cBack"));

        if (page > 0) {
            inv.setItem(48, createNavButton(Material.ARROW, "§7Previous Page (" + page + ")"));
        } else {
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = glass.getItemMeta(); m.setDisplayName("§7"); glass.setItemMeta(m);
            inv.setItem(48, glass);
        }

        if ((page + 1) * 28 < allTargets.size()) {
            inv.setItem(50, createNavButton(Material.ARROW, "§7Next Page (" + (page + 2) + ")"));
        } else {
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = glass.getItemMeta(); m.setDisplayName("§7"); glass.setItemMeta(m);
            inv.setItem(50, glass);
        }

        p.openInventory(inv);
    }

    private List<Object> getTargetsForCategory(Category cat, String subCat) {
        if (cat == Category.ITEMS && subCat != null) {
            switch (subCat) {
                case "End Items (General)":
                    return validItems.stream()
                            .filter(this::isEndItem)
                            .filter(m -> !isShulkerBox(m))
                            .filter(m -> m != Material.FRIEND_POTTERY_SHERD)
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Shulkerboxes":
                    return validItems.stream()
                            .filter(this::isShulkerBox)
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Mobheads":
                    return validItems.stream()
                            .filter(m -> (m.name().contains("HEAD") || m.name().contains("SKULL"))
                                    && !m.name().contains("PATTERN")
                                    && !m.name().contains("SHERD"))
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Banner Pattern":
                    return validItems.stream()
                            .filter(m -> m.name().contains("BANNER_PATTERN"))
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Froglights":
                    return validItems.stream()
                            .filter(m -> m.name().contains("FROGLIGHT"))
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Armortrims":
                    return validItems.stream()
                            .filter(m -> m.name().contains("ARMOR_TRIM_SMITHING_TEMPLATE"))
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Sherds":
                    return validItems.stream()
                            .filter(m -> m.name().contains("POTTERY_SHERD"))
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Schallplatten":
                    return validItems.stream()
                            .filter(m -> m.name().contains("MUSIC_DISC") || m.name().equals("DISC_FRAGMENT_5"))
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());

                case "Copper (Normal)":     return filterCopper(1, false);
                case "Copper (Exposed)":    return filterCopper(2, false);
                case "Copper (Weathered)":  return filterCopper(3, false);
                case "Copper (Oxidized)":   return filterCopper(4, false);

                case "Waxed Copper (Normal)":    return filterCopper(1, true);
                case "Waxed Copper (Exposed)":   return filterCopper(2, true);
                case "Waxed Copper (Weathered)": return filterCopper(3, true);
                case "Waxed Copper (Oxidized)":  return filterCopper(4, true);

                default:
                    return validItems.stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .collect(Collectors.toList());
            }
        }

        if (cat == Category.MOBS) {
            return validMobs.stream()
                    .sorted(Comparator.comparing(EntityType::name))
                    .collect(Collectors.toList());
        }

        if (cat == Category.BIOMES) {
            return Registry.BIOME.stream()
                    .sorted(Comparator.comparing(b -> b.getKey().getKey()))
                    .collect(Collectors.toList());
        }

        if (cat == Category.ACHIEVEMENT) {
            return validAdvancements.stream()
                    .sorted(Comparator.comparing(NamespacedKey::getKey))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>(validItems);
    }

    private ItemStack createNavButton(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack getIconForTarget(Category cat, Object target) {
        switch (cat) {
            case ITEMS: return new ItemStack((Material) target);
            case MOBS: return new ItemStack(getSpawnEgg((EntityType) target));
            case BIOMES: return new ItemStack(Material.GRASS_BLOCK);
            case ACHIEVEMENT: return new ItemStack(Material.WRITTEN_BOOK);
            default: return new ItemStack(Material.PAPER);
        }
    }

    private boolean isEndItem(Material m) {
        String name = m.name();
        return name.contains("END_") || name.contains("PURPUR") || name.contains("CHORUS")
                || name.contains("SHULKER") || name.contains("DRAGON") || m == Material.ELYTRA;
    }

    private int getCopperOxidationLevel(Material m) {
        String name = m.name();
        if (name.contains("OXIDIZED")) return 4;
        if (name.contains("WEATHERED")) return 3;
        if (name.contains("EXPOSED")) return 2;
        return 1;
    }

    public void openItemCategoryMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8Item Categories");
        fillGui(inv);

        inv.setItem(10, createStatusIcon(Material.END_STONE, "Enditem", Category.ITEMS, "End Items (General)"));
        inv.setItem(11, createStatusIcon(Material.COPPER_BLOCK, "Copper", Category.ITEMS, "Copper (Normal)"));
        inv.setItem(12, createStatusIcon(Material.ZOMBIE_HEAD, "Mobheads", Category.ITEMS, "Mobheads"));
        inv.setItem(13, createStatusIcon(Material.PIGLIN_BANNER_PATTERN, "Banner Pattern", Category.ITEMS, "Banner Pattern"));
        inv.setItem(14, createStatusIcon(Material.PEARLESCENT_FROGLIGHT, "Froglights", Category.ITEMS, "Froglights"));
        inv.setItem(15, createStatusIcon(Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE, "Armortrims", Category.ITEMS, "Armortrims"));
        inv.setItem(16, createStatusIcon(Material.ANGLER_POTTERY_SHERD, "Sherds", Category.ITEMS, "Sherds"));
        inv.setItem(17, createStatusIcon(Material.MUSIC_DISC_CAT, "Schallplatten", Category.ITEMS, "Schallplatten"));

        inv.setItem(22, createNavButton(Material.BARRIER, "§cBack to Main Menu"));
        p.openInventory(inv);
    }

    public void openCoordSettings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8Coordinate Settings");
        fillGui(inv);

        ItemStack radiusItem = new ItemStack(Material.COMPASS);
        ItemMeta meta = radiusItem.getItemMeta();
        meta.setDisplayName("§bRadius");
        meta.setLore(Arrays.asList(
                "§7Aktuell: §e" + state.minRadius + " - " + state.maxRadius + " Blöcke",
                "§7(Links: Min +50, Rechts: Min -50)",
                "§7(Shift-Links: Max +100, Shift-Rechts: Max -100)"
        ));
        radiusItem.setItemMeta(meta);
        inv.setItem(13, radiusItem);

        inv.setItem(22, createNavButton(Material.BARRIER, "§cBack to Main Menu"));
        p.openInventory(inv);
    }

    private boolean isShulkerBox(Material m) {
        return m.name().contains("SHULKER_BOX");
    }

    private List<Object> filterCopper(int level, boolean waxed) {
        return validItems.stream()
                .filter(m -> m.name().contains("COPPER") && !m.name().contains("INGOT") && !m.name().contains("ORE") && !m.name().contains("RAW"))
                .filter(m -> m.name().startsWith("WAXED_") == waxed)
                .filter(m -> getCopperOxidationLevel(m) == level)
                .collect(Collectors.toList());
    }

    public void openCopperCategoryMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 36, "§8Copper Categories");
        fillGui(inv);

        inv.setItem(10, createStatusIcon(Material.COPPER_BLOCK, "Copper (Normal)", Category.ITEMS, "Copper (Normal)"));
        inv.setItem(11, createStatusIcon(Material.EXPOSED_COPPER, "Copper (Exposed)", Category.ITEMS, "Copper (Exposed)"));
        inv.setItem(12, createStatusIcon(Material.WEATHERED_COPPER, "Copper (Weathered)", Category.ITEMS, "Copper (Weathered)"));
        inv.setItem(13, createStatusIcon(Material.OXIDIZED_COPPER, "Copper (Oxidized)", Category.ITEMS, "Copper (Oxidized)"));
        inv.setItem(14, createStatusIcon(Material.WAXED_COPPER_BLOCK, "Waxed Copper (Normal)", Category.ITEMS, "Waxed Copper (Normal)"));
        inv.setItem(15, createStatusIcon(Material.WAXED_EXPOSED_COPPER, "Waxed Copper (Exposed)", Category.ITEMS, "Waxed Copper (Exposed)"));
        inv.setItem(16, createStatusIcon(Material.WAXED_WEATHERED_COPPER, "Waxed Copper (Weathered)", Category.ITEMS, "Waxed Copper (Weathered)"));
        inv.setItem(17, createStatusIcon(Material.WAXED_OXIDIZED_COPPER, "Waxed Copper (Oxidized)", Category.ITEMS, "Waxed Copper (Oxidized)"));

        inv.setItem(31, createNavButton(Material.BARRIER, "§cBack to Items"));
        p.openInventory(inv);
    }

    public void openEndCategoryMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8End Categories");
        fillGui(inv);

        inv.setItem(11, createStatusIcon(Material.END_STONE, "End Items (General)", Category.ITEMS, "End Items (General)"));
        inv.setItem(15, createStatusIcon(Material.SHULKER_BOX, "Shulkerboxes", Category.ITEMS, "Shulkerboxes"));

        inv.setItem(22, createNavButton(Material.BARRIER, "§cBack to Items"));
        p.openInventory(inv);
    }

    private String getCategoryStatusPrefix(Category cat, String subName) {
        List<Object> all = getTargetsForCategory(cat, subName);
        if (all.isEmpty()) return "§a✔ ";

        Set<Object> disabled = state.disabledTargets.getOrDefault(cat, new HashSet<>());
        int disabledCount = 0;
        for (Object o : all) {
            if (disabled.contains(o)) disabledCount++;
        }

        if (disabledCount == 0) return "§a✔ ";
        if (disabledCount >= all.size()) return "§c✖ ";
        return "§e~ ";
    }

    private ItemStack createStatusIcon(Material m, String label, Category cat, String subName) {
        String prefix = getCategoryStatusPrefix(cat, subName);
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(prefix + "§f" + label);

        List<Object> all = getTargetsForCategory(cat, subName);
        Set<Object> disabled = state.disabledTargets.getOrDefault(cat, new HashSet<>());
        int activeCount = 0;
        for(Object o : all) if(!disabled.contains(o)) activeCount++;

        meta.setLore(Arrays.asList("§7Status: §e" + activeCount + " / " + all.size() + " aktiv", "§7(Shift+Klick: Ganze Gruppe toggeln)"));

        item.setItemMeta(meta);
        return item;
    }

    private void refreshAllGuis() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            InventoryView view = online.getOpenInventory();
            String title = view.getTitle();

            if (title.equals("§8Force Config")) openSetupGui(online);
            else if (title.equals("§8Force Probabilities")) openProbabilityGui(online);
            else if (title.equals("§8Item Categories")) openItemCategoryMenu(online);
            else if (title.equals("§8End Categories")) openEndCategoryMenu(online);
            else if (title.equals("§8Copper Categories")) openCopperCategoryMenu(online);
            else if (title.equals("§8Coordinate Settings")) openCoordSettings(online);
            else if (title.startsWith("§8Settings:")) {
                openSubMenu(online, getCategoryFromTitle(title), state.currentViewingPage, state.currentItemSubCategory);
            }
        }
    }

    public void saveSettings() {
        FileConfiguration data = plugin.getConfig();
        List<String> activeCats = state.activeCategories.stream().map(Enum::name).collect(Collectors.toList());
        data.set("settings.activeCategories", activeCats);
        data.set("settings.jokerCount", state.jokerCount);
        data.set("settings.allowDuplicates", state.allowDuplicates);
        data.set("settings.duplicateChance", state.duplicateChance);
        data.set("settings.minRadius", state.minRadius);
        data.set("settings.maxRadius", state.maxRadius);
        data.set("settings.giveItemOnSkip", state.giveItemOnSkip);

        for (Category c : Category.values()) {
            data.set("settings.weights." + c.name(), state.categoryWeights.getOrDefault(c, 10));
        }

        data.set("settings.disabledTargets", null);
        for (Map.Entry<Category, Set<Object>> entry : state.disabledTargets.entrySet()) {
            List<String> stringList = new ArrayList<>();
            for (Object obj : entry.getValue()) {
                if (obj instanceof Material) stringList.add("MAT:" + ((Material) obj).name());
                else if (obj instanceof org.bukkit.block.Biome) stringList.add("BIO:" + ((org.bukkit.block.Biome) obj).getKey().getKey());
                else if (obj instanceof NamespacedKey) stringList.add("KEY:" + ((NamespacedKey) obj).getKey());
                else if (obj instanceof EntityType) stringList.add("MOB:" + ((EntityType) obj).name());
            }
            data.set("settings.disabledTargets." + entry.getKey().name(), stringList);
        }
        plugin.saveConfig();
    }

    public void loadSettings() {
        FileConfiguration data = plugin.getConfig();
        if (data.contains("settings.activeCategories")) {
            state.activeCategories.clear();
            for (String s : data.getStringList("settings.activeCategories")) {
                try { state.activeCategories.add(Category.valueOf(s)); } catch (Exception ignored) {}
            }
        }
        if (data.contains("settings.jokerCount")) state.jokerCount = data.getInt("settings.jokerCount");
        if (data.contains("settings.allowDuplicates")) state.allowDuplicates = data.getBoolean("settings.allowDuplicates");
        if (data.contains("settings.duplicateChance")) state.duplicateChance = data.getInt("settings.duplicateChance");
        if (data.contains("settings.minRadius")) state.minRadius = data.getInt("settings.minRadius");
        if (data.contains("settings.maxRadius")) state.maxRadius = data.getInt("settings.maxRadius");
        if (data.contains("settings.giveItemOnSkip")) state.giveItemOnSkip = data.getBoolean("settings.giveItemOnSkip");

        if (data.contains("settings.weights")) {
            for (Category c : Category.values()) {
                if (data.contains("settings.weights." + c.name())) {
                    state.categoryWeights.put(c, data.getInt("settings.weights." + c.name()));
                }
            }
        }

        state.disabledTargets.clear();
        if (data.contains("settings.disabledTargets")) {
            for (String catKey : data.getConfigurationSection("settings.disabledTargets").getKeys(false)) {
                try {
                    Category cat = Category.valueOf(catKey);
                    Set<Object> targets = new HashSet<>();
                    for (String str : data.getStringList("settings.disabledTargets." + catKey)) {
                        try {
                            if (str.startsWith("MAT:")) targets.add(Material.valueOf(str.substring(4)));
                            else if (str.startsWith("BIO:")) targets.add(Registry.BIOME.get(NamespacedKey.minecraft(str.substring(4))));
                            else if (str.startsWith("KEY:")) targets.add(NamespacedKey.minecraft(str.substring(4)));
                            else if (str.startsWith("MOB:")) targets.add(EntityType.valueOf(str.substring(4)));
                        } catch (Exception ignored) {}
                    }
                    state.disabledTargets.put(cat, targets);
                } catch (Exception ignored) {}
            }
        }
    }
}