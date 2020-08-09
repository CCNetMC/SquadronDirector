package io.github.cccm5.managers;

import io.github.cccm5.Formation;
import io.github.cccm5.SquadronDirectorMain;
import io.github.cccm5.Utils;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.Rotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.utils.TeleportUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.material.Button;
import org.bukkit.material.Lever;
import org.bukkit.material.MaterialData;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.cccm5.SquadronDirectorMain.ERROR_TAG;
import static io.github.cccm5.SquadronDirectorMain.SUCCESS_TAG;

public class DirectorManager extends BukkitRunnable {

    private final ConcurrentHashMap<Player, CopyOnWriteArrayList<Craft>> directedCrafts = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Integer> playersStrafingUpDown = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Integer> playersStrafingLeftRight = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Formation> playerFormations = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Location> playersInReconSignLocation = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Craft> playersInReconParentCrafts = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, String> playersWeaponControl = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Integer> playersWeaponNumClicks = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Player, Integer> playersFormingUp = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Craft, Integer> pendingMoveDX = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Craft, Integer> pendingMoveDY = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Craft, Integer> pendingMoveDZ = new ConcurrentHashMap<>();

    private CopyOnWriteArrayList<Player> playersInLaunchMode = new CopyOnWriteArrayList<>();

    public DirectorManager() {
        runTaskTimerAsynchronously(SquadronDirectorMain.getInstance(), 20, 20);
    }

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
            Formation form = playerFormations.getOrDefault(p, null);
            if (form == Formation.ECHELON)
                formUpEchelon(p);
            else if (form == Formation.VIC) {
                formUpVic(p);
            }
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
                    signLoc= Utils.movecraftLocationToBukkitLocation(craft.getHitBox().getMidPoint(),craft.getW());
                }
                playersInReconSignLocation.put(p,signLoc);
            }

        }

        performPendingMoves();
        loadChunks();

    }

    public boolean playerControllingWeapon(Player player, String type) {
        return playersWeaponControl.containsKey(player) && playersWeaponControl.get(player) == type;
    }

    public boolean playerDirectingAnyCrafts(Player player) {
        return !directedCrafts.getOrDefault(player, new CopyOnWriteArrayList<>()).isEmpty();
    }

    public boolean playerInLaunchMode(Player player) {
        return playersInLaunchMode.contains(player);
    }

    public void removeDeadCrafts(Player player) {
        if(!playerDirectingAnyCrafts(player))
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

    public void strafeLeftRight(Craft c, Integer leftRight) {
        boolean bankLeft=(leftRight==1);
        boolean bankRight=(leftRight==2);
        int dx=0;
        int dz=0;
        Object cruiseDirection = c.getCruiseDirection();
        // ship faces west
        if (cruiseDirection.equals(0x5) || cruiseDirection == BlockFace.WEST) {
            if (bankRight) {
                dz = (-1 - c.getType().getCruiseSkipBlocks()) >> 1;
            }
            if (bankLeft) {
                dz = (1 + c.getType().getCruiseSkipBlocks()) >> 1;
            }
        }
        // ship faces east
        if (cruiseDirection.equals(0x4) || cruiseDirection == BlockFace.EAST) {
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

    public void cruiseToggle(Player player){
        if(directedCrafts.get(player)==null || directedCrafts.get(player).isEmpty()) {
            player.sendMessage(ERROR_TAG+"You have no squadron craft to direct");
            return;
        }
        for(Craft c : directedCrafts.get(player)) {
            if(c==null)
                continue;
            boolean setCruise = !c.getCruising();
            determineCruiseDirection(c);
            c.setCruising(setCruise);
        }
    }
    public void leverControl(Player player) {
        if(playerControllingWeapon(player, "LEVER")) {
            player.sendMessage(SUCCESS_TAG + "You have released lever control");
            playersWeaponControl.remove(player);
        } else {
            player.sendMessage(SUCCESS_TAG + "You are now controlling levers. In Recon Mode, right click to activate a lever on each craft");
            playersWeaponControl.put(player, "LEVER");
        }
    }

    public void buttonControl (Player player) {
        if(playerControllingWeapon(player, "LEVER")) {
            player.sendMessage(SUCCESS_TAG + "You have released button control");
            playersWeaponControl.remove(player);
        } else {
            player.sendMessage(SUCCESS_TAG + "You are now controlling buttons. In Recon Mode, right click to activate a button on each craft");
            playersWeaponControl.put(player, "LEVER");
        }
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
        String targString = null;
        if(playersWeaponControl.get(player).equals("LEVER")) {
            targBlockType= Lever.class;
        } else if(playersWeaponControl.get(player).equals("BUTTON")) {
            targBlockType= Button.class;
        } else {
            targString = playersWeaponControl.get(player);
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
        Location loc=new Location(player.getWorld(), leadX, leadY+5, leadZ);
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

    public void toggleFormUp(Player player, Formation formation, int spacing) {
        if (formation == null) {
            player.sendMessage(ERROR_TAG + "Invalid formation");
            return;
        }
        if(spacing > SquadronDirectorMain.getInstance().getConfig().getInt("Max spacing")) {
            player.sendMessage(ERROR_TAG + "Spacing is too high!");
            return;
        }
        if(playersFormingUp.containsKey(player) && playersFormingUp.get(player)==spacing) {
            playersFormingUp.remove(player);
            playerFormations.remove(player);
            player.sendMessage(SUCCESS_TAG+"No longer forming up");
        } else {
            playersFormingUp.put(player, spacing);
            playerFormations.put(player, formation);
            player.sendMessage(SUCCESS_TAG+"Forming up");
        }
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

    public void formUpVic(Player p) {
        int leadX=0;
        int leadY=0;
        int leadZ=0;
        int leadIndex=0;
        int leadDir=0;
        boolean leadIsCruising=false;
        int spacing=playersFormingUp.get(p);
        int craftIndex=-1;
        boolean leftOfLead = false;
        for(Craft c : directedCrafts.get(p)) {
            if (!leftOfLead)
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
                offset = leftOfLead ? -offset : offset;
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
                leftOfLead = !leftOfLead;
            }
        }
    }

    public void formUpEchelon(Player p) {
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

    private void loadChunks() {
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
        }.runTask(SquadronDirectorMain.getInstance());
    }

    public ConcurrentHashMap<Craft, Integer> getPendingMoveDX() {
        return pendingMoveDX;
    }

    public ConcurrentHashMap<Craft, Integer> getPendingMoveDY() {
        return pendingMoveDY;
    }

    public ConcurrentHashMap<Craft, Integer> getPendingMoveDZ() {
        return pendingMoveDZ;
    }

    public ConcurrentHashMap<Player, CopyOnWriteArrayList<Craft>> getDirectedCrafts() {
        return directedCrafts;
    }

    public ConcurrentHashMap<Player, Craft> getPlayersInReconParentCrafts() {
        return playersInReconParentCrafts;
    }

    public ConcurrentHashMap<Player, Formation> getPlayerFormations() {
        return playerFormations;
    }

    public ConcurrentHashMap<Player, Integer> getPlayersFormingUp() {
        return playersFormingUp;
    }

    public ConcurrentHashMap<Player, Integer> getPlayersStrafingLeftRight() {
        return playersStrafingLeftRight;
    }

    public ConcurrentHashMap<Player, Integer> getPlayersStrafingUpDown() {
        return playersStrafingUpDown;
    }

    public ConcurrentHashMap<Player, Integer> getPlayersWeaponNumClicks() {
        return playersWeaponNumClicks;
    }

    public ConcurrentHashMap<Player, Location> getPlayersInReconSignLocation() {
        return playersInReconSignLocation;
    }

    public ConcurrentHashMap<Player, String> getPlayersWeaponControl() {
        return playersWeaponControl;
    }
}
