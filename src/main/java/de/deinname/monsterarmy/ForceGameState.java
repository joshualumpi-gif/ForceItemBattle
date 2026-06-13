package de.deinname.monsterarmy;

import org.bukkit.boss.BossBar;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ForceGameState {

    public long startTime = 0;

    public List<ForceModule.Category> activeCategories = new ArrayList<>();
    public Map<ForceModule.Category, Integer> categoryWeights = new HashMap<>();
    public int jokerCount = 3;

    public Map<String, ForceModule.Task> currentTasks = new HashMap<>();
    public Map<String, List<ForceModule.HistoryEntry>> gameHistory = new HashMap<>();


    public Map<String, BossBar> teamBossBars = new HashMap<>();


    public List<String> rankedTeams = new ArrayList<>();


    public int currentResultIndex = 0;
    public Inventory currentResultInventory = null;
    public BukkitTask animationTask = null;

    public boolean giveItemOnSkip = false;


    public String currentViewingTeam = null;

    public int currentViewingPage = 0;

    public Set<String> finishedAnimations = new HashSet<>();

    public boolean allowDuplicates = false;
    public int duplicateChance = 10;

    public int minRadius = 100;
    public int maxRadius = 1000;

    public Map<ForceModule.Category, Set<Object>> disabledTargets = new HashMap<>();

    public String currentItemSubCategory = null;

    public ForceGameState() {
        for (ForceModule.Category c : ForceModule.Category.values()) {
            activeCategories.add(c);
            categoryWeights.put(c, 10);
        }
    }
}