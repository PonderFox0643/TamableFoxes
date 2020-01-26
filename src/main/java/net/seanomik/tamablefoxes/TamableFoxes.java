package net.seanomik.tamablefoxes;

import net.minecraft.server.v1_15_R1.*;
import net.seanomik.tamablefoxes.versions.version_1_15.command.CommandSpawnTamableFox;
import net.seanomik.tamablefoxes.io.Config;
import net.seanomik.tamablefoxes.io.LanguageConfig;
import net.seanomik.tamablefoxes.sqlite.SQLiteHandler;
import net.seanomik.tamablefoxes.versions.version_1_15.sqlite.SQLiteSetterGetter;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.*;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.craftbukkit.v1_15_R1.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_15_R1.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

// @TODO:

/* @CHANGELOG (1.5.1):
 *    Now loads on Linux
 *    Fixed a bug that would cause foxes to not be tamed after a server reboot
 *    Updated to 1.15.2
 */
public final class TamableFoxes extends JavaPlugin implements Listener {
    private static TamableFoxes plugin;
    public List<EntityTamableFox> spawnedFoxes = new ArrayList<>();

    public SQLiteSetterGetter sqLiteSetterGetter = new SQLiteSetterGetter();
    public SQLiteHandler sqLiteHandler = new SQLiteHandler();

    private boolean versionSupported = true;

    @Override
    public void onLoad() {
        plugin = this;

        LanguageConfig.getConfig().saveDefault();

        String version = Bukkit.getServer().getClass().getPackage().getName();
        if (!version.equals("org.bukkit.craftbukkit.v1_15_R1")) {
            Bukkit.getServer().getConsoleSender().sendMessage(Utils.getPrefix() + ChatColor.RED + LanguageConfig.getUnsupportedMCVersionRegister());
            versionSupported = false;
            return;
        }

        try { // Replace the fox entity
            Field field = EntityTypes.FOX.getClass().getDeclaredField("ba");
            field.setAccessible(true);

            // Remove the final modifier from the "ba" variable
            Field fieldMutable = field.getClass().getDeclaredField("modifiers");
            fieldMutable.setAccessible(true);
            fieldMutable.set(field, fieldMutable.getInt(field) & ~Modifier.FINAL);
            fieldMutable.setAccessible(false);

            field.set(EntityTypes.FOX, (EntityTypes.b<EntityFox>) (type, world) -> new EntityTamableFox(type, world));

            field.setAccessible(false);

            getServer().getConsoleSender().sendMessage(Utils.getPrefix() + ChatColor.GREEN + LanguageConfig.getSuccessReplaced());
        } catch (Exception e) {
            e.printStackTrace();
            getServer().getConsoleSender().sendMessage(Utils.getPrefix() + ChatColor.RED + LanguageConfig.getFailureReplace());
        }

    }

    @Override
    public void onEnable() {
        if (!versionSupported) {
            Bukkit.getServer().getConsoleSender().sendMessage(Utils.getPrefix() + ChatColor.RED + LanguageConfig.getUnsupportedMCVersionDisable());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("spawntamablefox").setExecutor(new CommandSpawnTamableFox(this));

        sqLiteSetterGetter.createTablesIfNotExist();
        this.saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    @Override
    public void onDisable() {
        getServer().getConsoleSender().sendMessage(Utils.getPrefix() + ChatColor.YELLOW + LanguageConfig.getSavingFoxMessage());
        sqLiteSetterGetter.saveFoxes(spawnedFoxes);
    }

    @EventHandler
    public void onWorldSaveEvent(WorldSaveEvent event) {
        sqLiteSetterGetter.saveFoxes(spawnedFoxes);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, ()-> {
            spawnedFoxes.addAll(sqLiteSetterGetter.loadFoxes(event.getChunk()));
        }, 5L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        for (EntityTamableFox fox : getFoxesOf(player)) {
            fox.setOwner((EntityLiving) ((CraftEntity) player).getHandle());
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();

        if (Utils.isTamableFox(entity)) {
            EntityTamableFox tamableFox = (EntityTamableFox) ((CraftEntity) entity).getHandle();

            spawnedFoxes.add(tamableFox);
        }
    }

    @EventHandler
    public void onPlayerInteractEntityEvent(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();

        if (event.getHand() != EquipmentSlot.HAND) return;

        player.sendMessage(entity.getUniqueId().toString());

        ItemStack itemHand = player.getInventory().getItemInMainHand();
        ItemMeta handMeta =  itemHand.getItemMeta();

        // Checks if the entity is EntityTamableFox and that the player is allowed to tame foxes
        if (Utils.isTamableFox(entity)) {
            EntityTamableFox tamableFox = (EntityTamableFox) ((CraftEntity) entity).getHandle();

            // Check if its tamed but ignore it if the player is holding sweet berries for breeding
            if (tamableFox.isTamed() && tamableFox.getOwner() != null && itemHand.getType() != Material.SWEET_BERRIES) {
                if (tamableFox.getOwner().getUniqueID() == player.getUniqueId()) {
                    if (player.isSneaking()) {
                        net.minecraft.server.v1_15_R1.ItemStack foxMouth = tamableFox.getEquipment(EnumItemSlot.MAINHAND);

                        if (foxMouth.isEmpty() && itemHand.getType() != Material.AIR) { // Giving an item
                            tamableFox.setMouthItem(itemHand);
                            itemHand.setAmount(itemHand.getAmount() - 1);
                        } else if (!foxMouth.isEmpty() && itemHand.getType() == Material.AIR) { // Taking the item
                            tamableFox.dropMouthItem();
                        } else if (!foxMouth.isEmpty() && itemHand.getType() != Material.AIR){ // Swapping items
                            // Drop item
                            tamableFox.dropMouthItem();

                            // Give item and take one away from player
                            tamableFox.setMouthItem(itemHand);
                            itemHand.setAmount(itemHand.getAmount() - 1);
                        }
                    } else if (itemHand.getType() == Material.NAME_TAG) {
                        tamableFox.setChosenName(handMeta.getDisplayName());
                    } else {
                        tamableFox.toggleSitting();
                    }

                    event.setCancelled(true);
                }
            } else if (itemHand.getType() == Material.CHICKEN && Config.canPlayerTameFox(player)) {
                if (Math.random() < 0.33D) { // tamed
                    tamableFox.setTamed(true);
                    tamableFox.setOwner(((CraftPlayer) player).getHandle());
                    // store uuid
                    player.getWorld().spawnParticle(Particle.HEART, entity.getLocation(), 6, 0.5D, 0.5D, 0.5D);

                    // Name fox
                    player.sendMessage(ChatColor.RED + ChatColor.BOLD.toString() + LanguageConfig.getTamedMessage());
                    player.sendMessage(ChatColor.RED + LanguageConfig.getTamingAskingName());
                    tamableFox.setChosenName("???");

                    //TamableFoxes.getPlugin().sqLiteSetterGetter.saveFox(tamableFox);

                    event.setCancelled(true);
                    new AnvilGUI.Builder()
                            .onComplete((plr, text) -> { // Called when the inventory output slot is clicked
                                if(!text.equals("")) {
                                    tamableFox.setChosenName(text);
                                    plr.sendMessage(Utils.getPrefix() + ChatColor.GREEN + LanguageConfig.getTamingChosenPerfect(text));

                                    TamableFoxes.getPlugin().sqLiteSetterGetter.saveFox(tamableFox);
                                }

                                return AnvilGUI.Response.close();
                            })
                            //.preventClose()      // Prevents the inventory from being closed
                            .text("Fox name")      // Sets the text the GUI should start with
                            .plugin(this)          // Set the plugin instance
                            .open(player);         // Opens the GUI for the player provided
                } else { // Tame failed
                    player.getWorld().spawnParticle(Particle.SMOKE_NORMAL, entity.getLocation(), 10, 0.3D, 0.3D, 0.3D, 0.15D);
                }

                if (!player.getGameMode().equals(GameMode.CREATIVE)) {
                    itemHand.setAmount(itemHand.getAmount() - 1);
                }

                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerBedEnterEvent(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        List<EntityTamableFox> foxesOf = getFoxesOf(player);

        for (EntityTamableFox tamableFox : foxesOf) {
            if (player.getWorld().getTime() > 12541L && player.getWorld().getTime() < 23460L) {
                tamableFox.setSleeping(true);
            }
        }
    }

    @EventHandler
    public void onPlayerBedLeaveEvent(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        List<EntityTamableFox> foxesOf = getFoxesOf(player);

        for (EntityTamableFox tamableFox : foxesOf) {
            tamableFox.setSleeping(false);
            if (tamableFox.isSitting()) {
                tamableFox.setSitting(true);
            }
        }
    }

    @EventHandler
    public void onEntityDeathEvent(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!Utils.isTamableFox(entity)) return; // Is the entity a tamable fox?

        // Remove the fox from storage
        spawnedFoxes.remove(entity);

        // Notify the owner
        EntityTamableFox tamableFox = (EntityTamableFox) ((CraftEntity) entity).getHandle();
        if (tamableFox.getOwner() != null) {
            Player owner = ((EntityPlayer) tamableFox.getOwner()).getBukkitEntity();
            owner.sendMessage(Utils.getPrefix() + ChatColor.RED + tamableFox.getChosenName() + " was killed!");
        }

        // Remove the fox from database
        //sqLiteSetterGetter.removeFox(tamableFox);
    }

    public EntityTamableFox spawnTamableFox(Location loc, EntityFox.Type type) {
        EntityTamableFox tamableFox = (EntityTamableFox) ((CraftEntity) loc.getWorld().spawnEntity(loc, EntityType.FOX)).getHandle();
        tamableFox.setFoxType(type);

        return tamableFox;
    }

    public List<EntityTamableFox> getFoxesOf(Player player) {
        return spawnedFoxes.stream().filter(fox -> fox.getOwner() != null && fox.getOwner().getUniqueID().equals(player.getUniqueId())).collect(Collectors.toList());
    }

    public static TamableFoxes getPlugin() {
        return plugin;
    }
}
