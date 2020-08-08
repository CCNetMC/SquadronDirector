package io.github.cccm5;

import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.Rotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.CraftType;
import net.countercraft.movecraft.craft.ICraft;
import net.countercraft.movecraft.events.CraftPilotEvent;
import net.countercraft.movecraft.listener.PlayerListener;
import net.countercraft.movecraft.utils.BitmapHitBox;
import net.countercraft.movecraft.utils.MathUtils;
import net.countercraft.movecraft.utils.TeleportUtils;
import net.minecraft.server.v1_10_R1.BlockPosition;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.v1_10_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_10_R1.block.CraftBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.material.Button;
import org.bukkit.material.Directional;
import org.bukkit.material.Lever;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
public class SquadronDirectorMain extends JavaPlugin implements Listener {
public static Logger logger;
public static final String ERROR_TAG = ChatColor.RED + "Error: " + ChatColor.DARK_RED;
public static final String SUCCESS_TAG = ChatColor.DARK_AQUA + "Squadron Director: " + ChatColor.WHITE;
private ConcurrentHashMap<Player, Integer> playersStrafingUpDown;
private ConcurrentHashMap<Player, Integer> playersStrafingLeftRight;
private ConcurrentHashMap<Player, String> playersWeaponControl;
private ConcurrentHashMap<Player, Integer> playersWeaponNumClicks;
private ConcurrentHashMap<Player, Integer> playersFormingUp;
private ConcurrentHashMap<Player, Craft> playersInReconParentCrafts;
private ConcurrentHashMap<Player, Location> playersInReconSignLocation;
private ConcurrentHashMap<Craft, Integer> pendingMoveDX;
private ConcurrentHashMap<Craft, Integer> pendingMoveDY;
private ConcurrentHashMap<Craft, Integer> pendingMoveDZ;
private CopyOnWriteArrayList<Player> playersInLaunchMode;
private ConcurrentHashMap<Player, CopyOnWriteArrayList<Craft>> directedCrafts;
private static SquadronDirectorMain instance;
private static Material SIGN_POST = Material.getMaterial("SIGN_POST");
private CraftManager craftManager;
private FileConfiguration config;
private boolean cardinalDistance;
private static boolean debug;


    public void onEnable() {
        logger = this.getLogger();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("sdrelease").setExecutor(this);
        this.getCommand("sdscuttle").setExecutor(this);
        this.getCommand("sdlaunch").setExecutor(this);
        this.getCommand("sdcruise").setExecutor(this);
        this.getCommand("sdrotate").setExecutor(this);
        this.getCommand("sdlever").setExecutor(this);
        this.getCommand("sdbutton").setExecutor(this);
        playersInLaunchMode = new CopyOnWriteArrayList<Player>();
        playersStrafingUpDown = new ConcurrentHashMap<Player, Integer>();
        playersStrafingLeftRight = new ConcurrentHashMap<Player, Integer>();
        playersWeaponControl = new ConcurrentHashMap<Player, String>();
        playersWeaponNumClicks = new ConcurrentHashMap<Player, Integer>();
        playersFormingUp = new ConcurrentHashMap<Player, Integer>();
        playersInReconParentCrafts = new ConcurrentHashMap<Player, Craft>();
        playersInReconSignLocation = new ConcurrentHashMap<Player, Location>();
        pendingMoveDX = new ConcurrentHashMap<Craft, Integer>();
        pendingMoveDY = new ConcurrentHashMap<Craft, Integer>();
        pendingMoveDZ = new ConcurrentHashMap<Craft, Integer>();
        directedCrafts = new ConcurrentHashMap<Player, CopyOnWriteArrayList<Craft>>();
        instance = this;
        //************************
        //* Check server version *
        //************************
        String packageName = getServer().getClass().getPackage().getName();
        String version = packageName.substring(packageName.lastIndexOf('.') + 1);
        String[] parts = version.split("_");

        //************************
        //*       Configs        *
        //************************
        config = getConfig();
        config.addDefault("Max crafts",12);
        config.addDefault("Max spacing",20);
        config.addDefault("Craft types", Arrays.asList("Airskiff","Subairskiff"));
        config.addDefault("Debug mode",false);
        config.options().copyDefaults(true);
        this.saveConfig();
        debug = config.getBoolean("Debug mode");
        //************************
        //*    Load Movecraft    *
        //************************
        if(getServer().getPluginManager().getPlugin("Movecraft") == null || !getServer().getPluginManager().getPlugin("Movecraft").isEnabled()) {
            logger.log(Level.SEVERE, "Movecraft not found or not enabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        craftManager = CraftManager.getInstance();

        // Run the asynch scheduled tasks. Should this be a separate class / method? Yes. Am I going to put it in one? No.
        new BukkitRunnable() {
            @Override
            public void run() {
                for(Player p : directedCrafts.keySet()) {
                    removeDeadCrafts(p);
                }

                // update the directed crafts every 10 seconds, or Movecraft will remove them due to inactivity
                for(CopyOnWriteArrayList<Craft> cl : directedCrafts.values()) {
                    for (Craft c : cl) {
                        if(c==null)
                            continue;
                        if(c.getCruising()==true)
                            continue;
                        if (System.currentTimeMillis() - c.getLastCruiseUpdate() > 10000) {
                            c.setLastCruiseUpdate(System.currentTimeMillis());
                        }
                    }
                }

                // now move any strafing crafts
                for(Player p : playersStrafingUpDown.keySet()) {
                    removeDeadCrafts(p);
                    for (Craft c : directedCrafts.get(p)) {
                        if(c==null)
                            continue;
                        if(playersStrafingUpDown.get(p)==1) {
                            updatePendingMove(c,0,1,0);
                        } else if(playersStrafingUpDown.get(p)==2) {
                            updatePendingMove(c,0,-1,0);
                        }
                    }
                }
                for(Player p : playersStrafingLeftRight.keySet()) {
                    for (Craft c : directedCrafts.get(p)) {
                        if(c==null)
                            continue;
                        determineCruiseDirection(c);
                        strafeLeftRight(c,playersStrafingLeftRight.get(p));

                    }
                }

                // and make the crafts form up that are supposed to
                for(Player p : playersFormingUp.keySet()) {
                    formUp(p);
                }

                // update the sign positions of any players in recon mode
                for(Player p : playersInReconSignLocation.keySet()) {
                    Location signLoc=null;
                    if(playersInReconParentCrafts.get(p)!=null) {
                        Craft craft=playersInReconParentCrafts.get(p);
                        for(MovecraftLocation tLoc : craft.getHitBox()) {
                            BlockState state = craft.getW().getBlockAt(tLoc.getX(), tLoc.getY(), tLoc.getZ()).getState();
                            if (state instanceof Sign) {
                                Sign s=(Sign) state;
                                if(s.getLine(1).equals(ChatColor.DARK_BLUE + "[Recon]")) {
                                    craft.setCruiseDirection(s.getRawData());
                                    signLoc=s.getLocation();
                                }
                            }
                        }
                        if(signLoc==null) {
                            signLoc=Utils.movecraftLocationToBukkitLocation(craft.getHitBox().getMidPoint(),craft.getW());
                        }
                        playersInReconSignLocation.put(p,signLoc);
                    }

                }

                performPendingMoves();

            }
        }.runTaskTimerAsynchronously(SquadronDirectorMain.getInstance(),20,20);

        // Run the synchronized scheduled tasks
        new BukkitRunnable() {
            @Override
            public void run() {
                // load any chunks near squadron crafts. This shouldn't be necessary, and should be removed once Movecraft is fixed to load chunks itself
                for(CopyOnWriteArrayList<Craft> cl : directedCrafts.values()) {
                    for (Craft c : cl) {
                        if(c==null)
                            continue;
                        if(c.getHitBox().isEmpty())
                            continue;
                        int minChunkX=c.getHitBox().getMinX()>>4;
                        int minChunkZ=c.getHitBox().getMinZ()>>4;
                        int maxChunkX=c.getHitBox().getMaxX()>>4;
                        int maxChunkZ=c.getHitBox().getMaxZ()>>4;
                        minChunkX--;
                        minChunkZ--;
                        maxChunkX++;
                        maxChunkZ++;
                        for(int chunkX=minChunkX; chunkX<=maxChunkX; chunkX++) {
                            for(int chunkZ=minChunkZ; chunkZ<=maxChunkZ; chunkZ++) {
                                if (!c.getW().isChunkLoaded(chunkX, chunkZ)) {
                                    c.getW().loadChunk(chunkX, chunkZ);
                                }
                            }
                        }
                    }
                }
                // load the chunks near the craft the director is on
                for (Craft c : playersInReconParentCrafts.values()) {
                    if(c==null)
                        continue;
                    if(c.getHitBox().isEmpty())
                        continue;
                    int minChunkX=c.getHitBox().getMinX()>>4;
                    int minChunkZ=c.getHitBox().getMinZ()>>4;
                    int maxChunkX=c.getHitBox().getMaxX()>>4;
                    int maxChunkZ=c.getHitBox().getMaxZ()>>4;
                    minChunkX--;
                    minChunkZ--;
                    maxChunkX++;
                    maxChunkZ++;
                    for(int chunkX=minChunkX; chunkX<=maxChunkX; chunkX++) {
                        for(int chunkZ=minChunkZ; chunkZ<=maxChunkZ; chunkZ++) {
                            if (!c.getW().isChunkLoaded(chunkX, chunkZ)) {
                                c.getW().loadChunk(chunkX, chunkZ);
                            }
                        }
                    }
                }

                // and move recon players into observation position and draw their HUD
                for(Player p : playersInReconParentCrafts.keySet()) {
                    handleReconPlayers(p);
                }
            }
        }.runTaskTimer(SquadronDirectorMain.getInstance(),20,20);
    }

    public void onDisable() {
        logger = null;
        instance = null;
    }

    public void updatePendingMove(Craft c, int dx, int dy, int dz) {
        if (pendingMoveDX.get(c) != null) {
            dx += pendingMoveDX.get(c);
        }
        pendingMoveDX.put(c,dx);
        if (pendingMoveDY.get(c) != null) {
            dy += pendingMoveDY.get(c);
        }
        pendingMoveDY.put(c,dy);
        if (pendingMoveDZ.get(c) != null) {
            dz += pendingMoveDZ.get(c);
        }
        pendingMoveDZ.put(c,dz);
    }

    public void performPendingMoves() {
        for(Craft c : pendingMoveDX.keySet()) {
            int dx=0;
            int dy=0;
            int dz=0;
            if(pendingMoveDX.get(c)!=null) {
                dx+=pendingMoveDX.get(c);
            }
            if(pendingMoveDY.get(c)!=null) {
                dy+=pendingMoveDY.get(c);
            }
            if(pendingMoveDZ.get(c)!=null) {
                dz+=pendingMoveDZ.get(c);
            }
            c.translate(dx, dy, dz);
        }
        pendingMoveDX.clear();
        pendingMoveDY.clear();
        pendingMoveDZ.clear();
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { // Plugin
        if(!(sender instanceof Player)){
            sender.sendMessage(ERROR_TAG + "You need to be a player to execute that command!");
            return true;
        }
        Player player= (Player) sender;
        if (command.getName().equalsIgnoreCase("sdrelease")) {

            if(!player.hasPermission("Squadron.command.release")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            releaseSquadrons(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdscuttle")) {

            if(!player.hasPermission("Squadron.command.scuttle")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            scuttleSquadrons(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdlaunch")) {

            if(!player.hasPermission("Squadron.command.launch")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            launchModeToggle(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdlever")) {
            if(!player.hasPermission("Squadron.command.lever")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            if((playersWeaponControl.get(player)!=null)&&(playersWeaponControl.get(player).equals("LEVER"))) {
                player.sendMessage(SUCCESS_TAG + "You have released lever control");
                playersWeaponControl.remove(player);
            } else {
                player.sendMessage(SUCCESS_TAG + "You are now controlling levers. In Recon Mode, right click to activate a lever on each craft");
                playersWeaponControl.put(player, "LEVER");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdbutton")) {

            if(!player.hasPermission("Squadron.command.button")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            if((playersWeaponControl.get(player)!=null)&&(playersWeaponControl.get(player).equals("BUTTON"))) {
                player.sendMessage(SUCCESS_TAG + "You have released button control");
                playersWeaponControl.remove(player);
            } else {
                player.sendMessage(SUCCESS_TAG + "You are now controlling buttons. In Recon Mode, right click to activate a button on each craft");
                playersWeaponControl.put(player, "BUTTON");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdascend")) {

            if(!player.hasPermission("Squadron.command.sdascend")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            ascendToggle(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sddescend")) {

            if(!player.hasPermission("Squadron.command.sddescend")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            descendToggle(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdcruise")) {

            if(!player.hasPermission("Squadron.command.cruise")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            cruiseToggle(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdrotate")) {

            if(!player.hasPermission("Squadron.command.rotate")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            if(args[0]==null) {
                player.sendMessage(ERROR_TAG + "You must specify right or left!");
                return true;
            }
            if(args[0].equalsIgnoreCase("left")) {
                rotateSquadron(player,Rotation.ANTICLOCKWISE);
            } else {
                rotateSquadron(player,Rotation.CLOCKWISE);
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sdformup")) {

            if(!player.hasPermission("Squadron.command.formup")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            Integer spacing=10;
            if(args.length!=0) {
                spacing=Integer.valueOf(args[0]);
            }
            if(spacing>config.getInt("Max spacing")) {
                player.sendMessage(ERROR_TAG + "Spacing is too high!");
                return true;
            }
            if(playersFormingUp.get(player)==spacing) {
                playersFormingUp.remove(player);
                player.sendMessage(SUCCESS_TAG+"No longer forming up");
            } else {
                playersFormingUp.put(player, spacing);
                player.sendMessage(SUCCESS_TAG+"Forming up");
            }

            return true;
        }
        return false;

    }


    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player player=e.getPlayer();
        if(playersInReconSignLocation.get(player)!=null) {
            if(player.getPotionEffect(PotionEffectType.INVISIBILITY)==null) {
                return;
            }
            if(player.getPotionEffect(PotionEffectType.INVISIBILITY).getDuration()>2479*20){ // wait a second before accepting any more move inputs
                return;
            }
            // make Movecraft not release the craft due to the player being in recon, and not on the craft
            Craft playerCraft=craftManager.getCraftByPlayer(player);
            if(playerCraft!=null) {
                HandlerList handlers=e.getHandlers();
                RegisteredListener[] listeners=handlers.getRegisteredListeners();
                for (RegisteredListener l : listeners) {
                    if (!l.getPlugin().isEnabled()) {
                        continue;
                    }
                    if(l.getListener() instanceof PlayerListener) {
                        PlayerListener pl= (PlayerListener) l.getListener();
                        Class plclass= PlayerListener.class;
                        try {
                            Field field = plclass.getDeclaredField("timeToReleaseAfter");
                            field.setAccessible(true);
                            final Map<Craft, Long> timeToReleaseAfter = (Map<Craft, Long>) field.get(pl);
                            if(timeToReleaseAfter.containsKey(playerCraft)) {
                                timeToReleaseAfter.put(playerCraft,System.currentTimeMillis() + 30000);
                            }
                        }
                        catch(Exception exception) {
                            exception.printStackTrace();
                        }
                    }
                }
            }
            Craft leadCraft=null;
            for(Craft c : directedCrafts.get(player)) {
                if ((c == null) || (c.getHitBox().isEmpty())) {
                    continue;
                }
                determineCruiseDirection(c);

                if (leadCraft == null) {
                    leadCraft=c;
                    break;
                }
            }
            if(leadCraft==null) {
                return;
            }

            double dx=e.getTo().getX()-e.getFrom().getX();
            double dy=e.getTo().getY()-e.getFrom().getY();
            double dz=e.getTo().getZ()-e.getFrom().getZ();
            if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
                return;
            }
            if(dy>0.07) {
                e.setCancelled(true);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 2480 * 20, 1, false, false));
                if(playersStrafingUpDown.get(player)==null) {
                    playersStrafingUpDown.put(player,1);
                    player.sendMessage(SUCCESS_TAG+"Ascent enabled");
                    return;
                }
                if(playersStrafingUpDown.get(player)==2) {
                    playersStrafingUpDown.remove(player);
                    player.sendMessage(SUCCESS_TAG+"Descent disabled");
                    return;
                }
            }
            if(dy<-0.07) {
                e.setCancelled(true);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 2480 * 20, 1, false, false));
                if(playersStrafingUpDown.get(player)==null) {
                    playersStrafingUpDown.put(player,2);
                    player.sendMessage(SUCCESS_TAG+"Descent enabled");
                    return;
                }
                if(playersStrafingUpDown.get(player)==1) {
                    playersStrafingUpDown.remove(player);
                    player.sendMessage(SUCCESS_TAG+"Ascent disabled");
                    return;
                }
            }
            // ship faces west
            if (leadCraft.getCruiseDirection() == 0x5) {
                if(dz<-0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==1) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dz>0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==2) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
            // ship faces east
            if (leadCraft.getCruiseDirection() == 0x4) {
                if(dz>0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==1) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dz<-0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==2) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
            // ship faces north
            if (leadCraft.getCruiseDirection() == 0x2) {
                if(dx<-0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==1) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dx>0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==2) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
            // ship faces south
            if (leadCraft.getCruiseDirection() == 0x3) {
                if(dx>0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==1) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dx<-0.07) {
                    e.setCancelled(true);
                    if(playersStrafingLeftRight.get(player)==null) {
                        playersStrafingLeftRight.put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(playersStrafingLeftRight.get(player)==2) {
                        playersStrafingLeftRight.remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }              }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Player player=e.getPlayer();

        if(player.getFlySpeed()<0.05) {     // check fly speed instead of playersinrecon in case someone got trapped in recon mode due to server restart or crash
            if((e.getAction()==Action.LEFT_CLICK_AIR)||(e.getAction()==Action.LEFT_CLICK_BLOCK)) {
                // they have to have been in recon mode for at least a second before removing it, or bad things could happen
                if((player.getPotionEffect(PotionEffectType.INVISIBILITY)==null)||(player.getPotionEffect(PotionEffectType.INVISIBILITY).getDuration()<2479*20)){
                    player.sendMessage(SUCCESS_TAG + "Leaving Recon Mode.");
                    leaveReconMode(player);
                    e.setCancelled(true);
                    return;
                }
            } else if (e.getAction()==Action.RIGHT_CLICK_AIR) {
                fireReconWeapons(player);
                e.setCancelled(true);
                return;
            }
        }

        if (!(e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction()==Action.LEFT_CLICK_BLOCK)) {
            return;
        }

        if (!e.getClickedBlock().getType().name().equals("SIGN_POST") && !e.getClickedBlock().getType().name().endsWith("SIGN") && !e.getClickedBlock().getType().name().endsWith("WALL_SIGN")) {
            return;
        }
        Sign sign = (Sign) e.getClickedBlock().getState();
        boolean signIsOnCraft=false;
        Craft craftSignIsOn=null;
        MovecraftLocation mloc = MathUtils.bukkit2MovecraftLoc(e.getClickedBlock().getLocation());
        CraftManager.getInstance().getCraftsInWorld(e.getClickedBlock().getWorld());
        for (Craft craft : CraftManager.getInstance().getCraftsInWorld(e.getClickedBlock().getWorld())) {
            if (craft == null || craft.getDisabled()) {
                continue;
            }
            for (MovecraftLocation tloc : craft.getHitBox()) {
                if (tloc.equals(mloc)) {
                    signIsOnCraft=true;
                    craftSignIsOn=craft;
                }
            }
        }

        if (sign.getLine(0).equals(ChatColor.DARK_AQUA + "SquadronDirector")) {
            if(!signIsOnCraft) {
                player.sendMessage(ERROR_TAG + "The command sign is not on a piloted craft!");
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Release]")) {
                if(!player.hasPermission("Squadron.sign.release")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                releaseSquadrons(player);
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Launch]")) {
                if(!player.hasPermission("Squadron.sign.launch")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                launchModeToggle(player);
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Cruise]")) {
                if(!player.hasPermission("Squadron.sign.cruise")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                cruiseToggle(player);
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Ascend]")) {
                if(!player.hasPermission("Squadron.sign.ascend")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                ascendToggle(player);
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Descend]")) {
                if(!player.hasPermission("Squadron.sign.Descend")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                descendToggle(player);
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Form Up]")) {
                if(!player.hasPermission("Squadron.sign.formup")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                Integer spacing=10;
                spacing=Integer.valueOf(sign.getLine(3));

                if(spacing>config.getInt("Max spacing")) {
                    player.sendMessage(ERROR_TAG + "Spacing is too high!");
                    return;
                }
                if(playersFormingUp.get(player)==spacing) {
                    playersFormingUp.remove(player);
                    player.sendMessage(SUCCESS_TAG+"No longer forming up");
                } else {
                    playersFormingUp.put(player, spacing);
                    player.sendMessage(SUCCESS_TAG+"Forming up");
                }
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Rotate]")) {
                if(!player.hasPermission("Squadron.sign.rotate")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                if(e.getAction()==Action.LEFT_CLICK_BLOCK) {
                    rotateSquadron(player,Rotation.ANTICLOCKWISE);
                } else {
                    rotateSquadron(player,Rotation.CLOCKWISE);
                }
                e.setCancelled(true);
                return;
            }

            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Recon]")) {
                if(!player.hasPermission("Squadron.sign.recon")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
                    player.sendMessage(ERROR_TAG+"You have no squadron craft to recon!");
                    return;
                }
                playersInReconParentCrafts.put(player,craftSignIsOn);
                playersInReconSignLocation.put(player,player.getLocation().clone());
                player.sendMessage(SUCCESS_TAG+"You have entered Recon Mode. Left click leaves recon mode, right click triggers any active weapon systems. Strafing up, down, left, or right will move the squadron.");
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 2480 * 20, 1, false, false));
                player.setInvulnerable(true);
                player.setWalkSpeed((float) 0.01);
                player.setFlySpeed((float) 0.01);
                e.setCancelled(true);
                return;
            }
            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Lever]")) {
                if (!player.hasPermission("Squadron.sign.lever")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                if((playersWeaponControl.get(player)!=null)&&(playersWeaponControl.get(player).equals("LEVER"))) {
                    player.sendMessage(SUCCESS_TAG + "You have released lever control");
                    playersWeaponControl.remove(player);
                } else {
                    player.sendMessage(SUCCESS_TAG + "You are now controlling levers. In Recon Mode, right click to activate a lever on each craft");
                    playersWeaponControl.put(player, "LEVER");
                }
                return;
            }
            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Button]")) {
                if (!player.hasPermission("Squadron.sign.button")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                if((playersWeaponControl.get(player)!=null)&&(playersWeaponControl.get(player).equals("BUTTON"))) {
                    player.sendMessage(SUCCESS_TAG + "You have released button control");
                    playersWeaponControl.remove(player);
                } else {
                    player.sendMessage(SUCCESS_TAG + "You are now controlling buttons. In Recon Mode, right click to activate a button on each craft");
                    playersWeaponControl.put(player, "BUTTON");
                }
                return;
            }
            if(sign.getLine(1).equals(ChatColor.DARK_BLUE + "[Remote Sign]")) {
                if (!player.hasPermission("Squadron.sign.remote")) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to use that sign!");
                    return;
                }
                String targString=sign.getLine(2);
                if((playersWeaponControl.get(player)!=null)&&(playersWeaponControl.get(player).equalsIgnoreCase(targString))) {
                    player.sendMessage(SUCCESS_TAG + "You have released remote sign control");
                    playersWeaponControl.remove(player);
                } else {
                    player.sendMessage(SUCCESS_TAG + "You are now controlling "+targString+" signs. In Recon Mode, right click to activate a sign on each craft");
                    playersWeaponControl.put(player, targString);
                }
                return;
            }

            player.sendMessage(ERROR_TAG + "Squadron Director sign not recognized!");
            return;
        }

        // now check to see if they are trying to launch a new craft
        if(playersInLaunchMode.contains(e.getPlayer()) && e.getAction()==Action.LEFT_CLICK_BLOCK) {

            String foundCraft = null;
            for(String craftName : config.getStringList("Craft types")) {
                if(sign.getLine(0).equalsIgnoreCase(craftName)) {
                    foundCraft = craftName;
                }
            }
            if(foundCraft!=null) {
                if(!player.hasPermission("Squadron."+foundCraft)) {
                    player.sendMessage(ERROR_TAG + "You do not have permissions to direct that craft type!");
                    return;
                }
            if(foundCraft==null) {
                return;
            }
            if(directedCrafts.get(player)!=null) {
                if(directedCrafts.get(player).size()==config.getInt("Max crafts")) {
                    player.sendMessage(ERROR_TAG + "You are already directing the maximum number of crafts!");
                    return;
                }
            }

            if(signIsOnCraft) {
                if(CraftManager.getInstance().getPlayerFromCraft(craftSignIsOn)==null) {
                    player.sendMessage(ERROR_TAG + "This craft is already being directed!");
                    return;
                }
            }

            // now try to detect the craft
            Location loc = e.getClickedBlock().getLocation();
            MovecraftLocation startPoint = new MovecraftLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            CraftType type = CraftManager.getInstance().getCraftTypeFromString(foundCraft);
            final Craft c = new ICraft(type, loc.getWorld());

            if (c.getType().getCruiseOnPilot()) {
                player.sendMessage(ERROR_TAG + "You can not direct a CruiseOnPilot craft!");
                return;
            }

            // determine the cruise direction of the craft, release it if there is no cruise or helm sign
            Craft finalCraftSignIsOn = craftSignIsOn;
            new BukkitRunnable() {
                @Override
                public void run() {
                    determineCruiseDirection(c);
                }
            }.runTaskLater(SquadronDirectorMain.getInstance(), (10));


            if(signIsOnCraft) { // stop the parent craft from moving during detection, and remove the child craft from the parent to prevent overlap
                craftSignIsOn.setProcessing(true);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        BitmapHitBox parentHitBox= finalCraftSignIsOn.getHitBox();
                        parentHitBox.removeAll(c.getHitBox());
                    }
                }.runTaskLater(SquadronDirectorMain.getInstance(), (10));
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        finalCraftSignIsOn.setProcessing(false);
                    }
                }.runTaskLater(SquadronDirectorMain.getInstance(), (20));
            }
            c.detect(null, e.getPlayer(), startPoint);
            Bukkit.getServer().getPluginManager().callEvent(new CraftPilotEvent(c, CraftPilotEvent.Reason.PLAYER));

            CopyOnWriteArrayList <Craft>cl=directedCrafts.get(player);
            if(cl==null) {
                cl=new CopyOnWriteArrayList <Craft>();
            }
            cl.add(c);
            directedCrafts.put(player,cl);
            c.setLastCruiseUpdate(System.currentTimeMillis());
            player.sendMessage(SUCCESS_TAG+"You have attempted to launch a craft of type "+foundCraft);

            e.setCancelled(true);
            return;
            }
        }
    }

    @EventHandler
    public void onSignPlace(SignChangeEvent e){
        if(!e.getBlock().getType().name().equals("SIGN_POST") && !e.getBlock().getType().name().endsWith("SIGN") && !e.getBlock().getType().name().endsWith("WALL_SIGN")){
            return;
        }
        if(ChatColor.stripColor(e.getLine(0)).equalsIgnoreCase("SquadronDirector") ){
            e.setLine(0,ChatColor.DARK_AQUA + "SquadronDirector");
            if(ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[release]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Release]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[launch]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Launch]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Cruise]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Cruise]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Rotate]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Rotate]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Lever]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Lever]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Button]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Button]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Remote Sign]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Remote Sign]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Recon]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Recon]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Ascend]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Ascend]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Descend]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Descend]");
            } else if (ChatColor.stripColor(e.getLine(1)).equalsIgnoreCase("[Form Up]") ){
                e.setLine(1,ChatColor.DARK_BLUE + "[Form Up]");
                e.setLine(2,"Echelon");
                if(e.getLine(3).isEmpty()) {
                    e.setLine(3,"10");
                }
            } else {
                e.getPlayer().sendMessage(ERROR_TAG + "Squadron Director sign not recognized!");
            }

        }

    }

    public static boolean isDebug(){
        return debug;
    }

    public static SquadronDirectorMain getInstance(){
        return instance;
    }

    private int bukkitDirToClockwiseDir(byte dir) {
        if(dir==0x2) // north
            return 0;
        if(dir==0x4) // east
            return 1;
        if(dir==0x3) // south
            return 2;
        // west
        return 3;
    }

    public void formUp(Player p) {
        int leadX=0;
        int leadY=0;
        int leadZ=0;
        int leadIndex=0;
        int leadDir=0;
        boolean leadIsCruising=false;
        int spacing=playersFormingUp.get(p);
        int craftIndex=-1;
        for(Craft c : directedCrafts.get(p)) {
            craftIndex++;
            if((c==null)||(c.getHitBox().isEmpty())) {
                continue;
            }
            determineCruiseDirection(c);

            if(leadY==0) { // if it's the lead craft, store it's info. If it isn't adjust it's heading and position
                leadX=c.getHitBox().getMidPoint().getX();
                leadY=c.getHitBox().getMidPoint().getY();
                leadZ=c.getHitBox().getMidPoint().getZ();
                leadIndex=craftIndex;
                leadDir=bukkitDirToClockwiseDir(c.getCruiseDirection());
                leadIsCruising=c.getCruising();
            } else {
                // rotate the crafts to face the direction the lead craft is facing
                int craftDir = bukkitDirToClockwiseDir(c.getCruiseDirection());
                if(craftDir!=leadDir) {
                    if (Math.abs(craftDir - leadDir) == 1 || Math.abs(craftDir - leadDir) == 3) { // are they close?
                        if (craftDir - leadDir == -1 || craftDir - leadDir == 3) {
                            c.rotate(Rotation.ANTICLOCKWISE, c.getHitBox().getMidPoint());
                        } else {
                            c.rotate(Rotation.CLOCKWISE, c.getHitBox().getMidPoint());
                        }
                    } else if (craftDir != leadDir) {
                        c.rotate(Rotation.CLOCKWISE, c.getHitBox().getMidPoint()); // if they aren't close, the direction doesn't matter
                    }
//                    determineCruiseDirection(c);
                }

                // move the crafts to their position in formation
                int posInFormation = craftIndex - leadIndex;
                int offset = posInFormation * spacing;
                int targX = leadX + offset;
                int targY = leadY + (offset >> 1);
                int targZ = leadZ + offset;

                int dx = 0;
                int dy = 0;
                int dz = 0;

                if (c.getHitBox().getMidPoint().getX() < targX) {
                    if(targX-c.getHitBox().getMidPoint().getX()==1) {
                        dx = 1;
                    } else {
                        dx = 2;
                    }
                } else if (c.getHitBox().getMidPoint().getX() > targX) {
                    if(targX-c.getHitBox().getMidPoint().getX()==-1) {
                        dx = -1;
                    } else {
                        dx = -2;
                    }
                }
                if (c.getHitBox().getMidPoint().getY() < targY) {
                    if(targY-c.getHitBox().getMidPoint().getY()==1) {
                        dy = 1;
                    } else {
                        dy = 2;
                    }
                } else if (c.getHitBox().getMidPoint().getY() > targY) {
                    if(targY-c.getHitBox().getMidPoint().getY()==-1) {
                        dy = -1;
                    } else {
                        dy = -2;
                    }
                }
                if (c.getHitBox().getMidPoint().getZ() < targZ) {
                    if(targZ-c.getHitBox().getMidPoint().getZ()==1) {
                        dz = 1;
                    } else {
                        dz = 2;
                    }
                } else if (c.getHitBox().getMidPoint().getZ() > targZ) {
                    if(targZ-c.getHitBox().getMidPoint().getZ()==-1) {
                        dz = -1;
                    } else {
                        dz = -2;
                    }
                }
                updatePendingMove(c, dx,dy,dz);

                // set cruising to whatever the lead is doing
                c.setCruising(leadIsCruising);
            }
        }

    }

    public void strafeLeftRight(Craft c, Integer leftRight) {
        boolean bankLeft=(leftRight==1);
        boolean bankRight=(leftRight==2);
        int dx=0;
        int dz=0;
        // ship faces west
        if (c.getCruiseDirection() == 0x5) {
            if (bankRight) {
                dz = (-1 - c.getType().getCruiseSkipBlocks()) >> 1;
            }
            if (bankLeft) {
                dz = (1 + c.getType().getCruiseSkipBlocks()) >> 1;
            }
        }
        // ship faces east
        if (c.getCruiseDirection() == 0x4) {
            if (bankLeft) {
                dz = (-1 - c.getType().getCruiseSkipBlocks()) >> 1;
            }
            if (bankRight) {
                dz = (1 + c.getType().getCruiseSkipBlocks()) >> 1;
            }
        }
        // ship faces north
        if (c.getCruiseDirection() == 0x2) {
            if (bankRight) {
                dx = (-1 - c.getType().getCruiseSkipBlocks()) >> 1;
            }
            if (bankLeft) {
                dx = (1 + c.getType().getCruiseSkipBlocks()) >> 1;
            }
        }
        // ship faces south
        if (c.getCruiseDirection() == 0x3) {
            if (bankLeft) {
                dx = (-1 - c.getType().getCruiseSkipBlocks()) >> 1;
            }
            if (bankRight) {
                dx = (1 + c.getType().getCruiseSkipBlocks()) >> 1;
            }
        }
        updatePendingMove(c,dx,0,dz);
    }

    public void releaseSquadrons(Player player){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to release");
            return;
        }
        int numCraft=0;
        for(Craft c : directedCrafts.get(player)) {
            CraftManager.getInstance().removeCraft(c);
            numCraft++;
        }
        playersFormingUp.remove(player);
        playersStrafingUpDown.remove(player);
        playersStrafingLeftRight.remove(player);
        directedCrafts.get(player).clear();
        if(numCraft>1) {
            player.sendMessage(SUCCESS_TAG+"You have released "+numCraft+" squadron crafts");
        } else if(numCraft>0) {
            player.sendMessage(SUCCESS_TAG+"You have released "+numCraft+" squadron craft");
        } else {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to release");
        }
    }

    public void scuttleSquadrons(Player player){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to scuttle");
            return;
        }
        int numCraft=0;
        for(Craft c : directedCrafts.get(player)) {
            c.sink();
            numCraft++;
        }
        playersFormingUp.remove(player);
        playersStrafingUpDown.remove(player);
        playersStrafingLeftRight.remove(player);
        directedCrafts.get(player).clear();
        if(numCraft>1) {
            player.sendMessage(SUCCESS_TAG+"You have scuttled "+numCraft+" squadron crafts");
        } else if(numCraft>0) {
            player.sendMessage(SUCCESS_TAG+"You have scuttled "+numCraft+" squadron craft");
        } else {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to scuttle");
        }
    }

    public void removeDeadCrafts(Player player) {
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty())
            return;
        CopyOnWriteArrayList <Craft> craftsToRemove=new CopyOnWriteArrayList<Craft>();

        for(Craft c : directedCrafts.get(player)) {
            if (c == null) {
                craftsToRemove.add(c);
                continue;
            }
            if (c.getHitBox() == null || c.getSinking()) {
                craftsToRemove.add(c);
            }
        }
        directedCrafts.get(player).removeAll(craftsToRemove);
    }

    public void launchModeToggle(Player player){
        if(playersInLaunchMode.contains(player)) {
            playersInLaunchMode.remove(player);
            player.sendMessage(SUCCESS_TAG+"You have left Launch Mode.");
        } else {
            playersInLaunchMode.add(player);
            player.sendMessage(SUCCESS_TAG+"You have entered Launch Mode. Left click crafts you wish to direct.");
        }
        return;
    }

    public void cruiseToggle(Player player){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to direct");
            return;
        }
        Boolean setCruise=null;
        for(Craft c : directedCrafts.get(player)) {
            if(c==null)
                continue;
            determineCruiseDirection(c);
            if(setCruise==null) {
                setCruise=!(c.getCruising());
            }
            c.setCruising(setCruise);
        }
        return;
    }

    public void ascendToggle(Player player){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to direct");
            return;
        }
        if(playersStrafingUpDown.get(player)==null) {
            playersStrafingUpDown.put(player,1);
            player.sendMessage(SUCCESS_TAG+"Ascent enabled");
            return;
        }
        if(playersStrafingUpDown.get(player)==1) {
            playersStrafingUpDown.remove(player);
            player.sendMessage(SUCCESS_TAG+"Ascent disabled");
            return;
        }
        playersStrafingUpDown.put(player,1);
        player.sendMessage(SUCCESS_TAG+"Ascent enabled");
        return;
    }

    public void descendToggle(Player player){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to direct");
            return;
        }
        if(playersStrafingUpDown.get(player)==null) {
            playersStrafingUpDown.put(player,2);
            player.sendMessage(SUCCESS_TAG+"Descent enabled");
            return;
        }
        if(playersStrafingUpDown.get(player)==2) {
            playersStrafingUpDown.remove(player);
            player.sendMessage(SUCCESS_TAG+"Descent disabled");
            return;
        }
        playersStrafingUpDown.put(player,1);
        player.sendMessage(SUCCESS_TAG+"Descent enabled");
        return;
    }

    public void leaveReconMode(Player player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY);

        player.setWalkSpeed((float)0.2);
        player.setFlySpeed((float)0.1);

        if(playersInReconSignLocation.get(player)!=null) {
            TeleportUtils.teleport(player, playersInReconSignLocation.get(player), (float) 0.0);
        }

        if(player.getGameMode() != GameMode.CREATIVE) {
            player.setFlying(false);
            player.setAllowFlight(false);
            player.setInvulnerable(false);
        }

        playersInReconParentCrafts.remove(player);
        playersInReconSignLocation.remove(player);
    }

    public void fireReconWeapons(Player player) {
        if(playersWeaponControl.get(player)==null) {
            return;
        }
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to direct");
            return;
        }

        Class targBlockType=null;
        String targString=null;
        if(playersWeaponControl.get(player).equals("LEVER")) {
            targBlockType= Lever.class;
        } else if(playersWeaponControl.get(player).equals("BUTTON")) {
            targBlockType= Button.class;
        } else {
            targString=playersWeaponControl.get(player);
        }
        int numFound=0;
        if(playersWeaponNumClicks.get(player)==null) {
            playersWeaponNumClicks.put(player,0);
        } else {
            playersWeaponNumClicks.put(player, playersWeaponNumClicks.get(player)+1);
        }
        for(Craft craft : directedCrafts.get(player)) {
            ArrayList<Block> targBlocks=new ArrayList<Block>();

            for(MovecraftLocation tLoc : craft.getHitBox()) {
                Block block = craft.getW().getBlockAt(tLoc.getX(), tLoc.getY(), tLoc.getZ());
                if(targString!=null) {
                    BlockState state = block.getState();
                    if(state instanceof Sign) {
                        Sign s = (Sign) state;
                        if (ChatColor.stripColor(s.getLine(0)).equalsIgnoreCase(targString)
                                || ChatColor.stripColor(s.getLine(1)).equalsIgnoreCase(targString)
                                || ChatColor.stripColor(s.getLine(2)).equalsIgnoreCase(targString)
                                || ChatColor.stripColor(s.getLine(3)).equalsIgnoreCase(targString)) {
                            targBlocks.add(block);
                            numFound++;
                        }

                    }
                } else {
                    MaterialData mat=block.getState().getData();
                    if(targBlockType.isInstance(mat)) {
                        targBlocks.add(block);
                        numFound++;
                        break;
                    }
                }
            }
            if(playersWeaponControl.get(player).equals("LEVER")) {
                for(Block block : targBlocks) {
                    byte data=block.getData();
                    if(data>=8)
                        data-=8;
                    else
                        data+=8;
                    block.setData(data, true);  // the non-deprecated methods are not working as of 6/24/2020
                    block.getState().update(true, true);
                }
            } else if(playersWeaponControl.get(player).equals("BUTTON")) {
                for(Block block : targBlocks) {
                    byte data=block.getData();
                    if(data>=8)
                        data-=8;
                    else
                        data+=8;
                    block.setData(data, true);  // the non-deprecated methods are not working as of 6/24/2020
                    block.getState().update(true, true);
                }
            } else {
                Block targBlock = targBlocks.get(playersWeaponNumClicks.get(player) % numFound); // the point of this is to activate a different sign each time you click

                PlayerInteractEvent newEvent = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getItemOnCursor(), targBlock, BlockFace.EAST);
                Bukkit.getServer().getPluginManager().callEvent(newEvent);
            }
        }
        if(numFound==0) {
            player.sendMessage(ERROR_TAG+"No triggers found for firing");
            return;
        }

        player.sendMessage(SUCCESS_TAG+"Triggered "+numFound+" devices");
    }

    public void handleReconPlayers(Player player) {
        if((directedCrafts.get(player)==null) || (directedCrafts.get(player).isEmpty())) {
            player.sendMessage(ERROR_TAG+"You no longer have any craft to direct. Leaving Recon Mode.");
            leaveReconMode(player);
            return;
        }

        int leadX=0;
        int leadY=0;
        int leadZ=0;
        int craftIndex=-1;
        for(Craft c : directedCrafts.get(player)) {
            craftIndex++;
            if((c==null)||(c.getHitBox().isEmpty())) {
                continue;
            }
            determineCruiseDirection(c);

            if(leadY==0) {
                leadX=c.getHitBox().getMidPoint().getX();
                leadY=c.getHitBox().getMidPoint().getY();
                leadZ=c.getHitBox().getMidPoint().getZ();
            }
        }
        Location loc=new Location(player.getWorld(), leadX, leadY+50, leadZ);
        player.setAllowFlight(true);
        player.setFlying(true);
        TeleportUtils.teleport(player,loc,(float)0.0);
        // TODO: Draw a HUD for weapons control
    }

    public void rotateSquadron(Player player, Rotation rotation){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to direct");
            return;
        }

        for(Craft c : directedCrafts.get(player)) {
            c.rotate(rotation, c.getHitBox().getMidPoint());
            determineCruiseDirection(c);
        }
        return;
    }

    public void determineCruiseDirection(Craft craft) {
        if(craft==null)
            return;
        boolean foundCruise=false;
        boolean foundHelm=false;
        for(MovecraftLocation tLoc : craft.getHitBox()) {
            BlockState state = craft.getW().getBlockAt(tLoc.getX(), tLoc.getY(), tLoc.getZ()).getState();
            if (state instanceof Sign) {
                Sign s=(Sign) state;
                if(s.getLine(0).equalsIgnoreCase("Cruise: OFF") || s.getLine(0).equalsIgnoreCase("Cruise: ON")) {
                    craft.setCruiseDirection(s.getRawData());
                    foundCruise=true;
                }
                if(ChatColor.stripColor(s.getLine(0)).equals("\\  ||  /") &&
                            ChatColor.stripColor(s.getLine(1)).equals("==      ==") &&
                            ChatColor.stripColor(s.getLine(2)).equals("/  ||  \\")) {
                    foundHelm=true;
                }
            }
        }
        if(!foundCruise) {
            craft.getNotificationPlayer().sendMessage(ERROR_TAG+"This craft has no Cruise sign and can not be directed");
            CraftManager.getInstance().removeCraft(craft);
        }
        if(!foundHelm) {
            craft.getNotificationPlayer().sendMessage(ERROR_TAG+"This craft has no Helm sign and can not be directed");
            CraftManager.getInstance().removeCraft(craft);
        }
    }
}
