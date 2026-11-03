package haven;

import java.util.Locale;

/**
 * H4D: while "Block Stone Actions while carrying Rock Crystal" is on and a Rock Crystal is in the
 * main inventory, drop the outgoing widget messages that would let the server take stone from
 * the inventory (and so possibly eat the crystal): crafting a recipe with a stone input, pressing
 * "Build" on a construction site, and starting the Landscape (paving) tool.
 * See doc/H4D-features/rock-crystal-guard.md.
 */
public class RockCrystalGuard {
    public static final String DENIED = "Action denied, please remove Rock Crystal to continue your actions.";
    private static final String CRYSTAL_RES = "gfx/invobjs/rockcrystal";
    private static final double MSG_COOLDOWN = 1.0;
    private static double lastmsg = 0;

    public static boolean enabled() {
	return(OptWnd.blockStoneActionsWithRockCrystalCheckBox != null && OptWnd.blockStoneActionsWithRockCrystalCheckBox.a);
    }

    /** Called from UI.wdgmsg before a message goes to the server. True = drop it. */
    public static boolean blocks(UI ui, Widget sender, String msg, Object[] args) {
	if(!enabled() || ui == null || ui.gui == null)
	    return(false);
	if(!isStoneAction(sender, msg, args))
	    return(false);
	if(!carryingRockCrystal(ui.gui))
	    return(false);
	if(sender instanceof Makewindow)
	    ((Makewindow)sender).stopBatch(null);
	double now = Utils.rtime();
	if(now - lastmsg > MSG_COOLDOWN) {
	    lastmsg = now;
	    ui.gui.error(DENIED);
	}
	return(true);
    }

    private static boolean isStoneAction(Widget sender, String msg, Object[] args) {
	if(sender instanceof Makewindow && "make".equals(msg))
	    return(recipeUsesStone((Makewindow)sender));
	if(sender instanceof Button && "activate".equals(msg)) {
	    Text t = ((Button)sender).text;
	    return(t != null && "Build".equals(t.text));
	}
	if(sender instanceof MenuGrid && "act".equals(msg) && args.length > 0)
	    return("landscape".equals(args[0]));
	return(false);
    }

    public static boolean carryingRockCrystal(GameUI gui) {
	Inventory inv = gui.maininv;
	if(inv == null)
	    return(false);
	for(WItem w : inv.getAllItems()) {
	    try {
		Resource res = w.item.getres();
		if(res != null && res.name.equals(CRYSTAL_RES))
		    return(true);
	    } catch(Loading l) {
	    }
	    String nm = w.item.getname();
	    if(nm != null && nm.equalsIgnoreCase("Rock Crystal"))
		return(true);
	}
	return(false);
    }

    private static boolean recipeUsesStone(Makewindow wnd) {
	for(Makewindow.Input in : wnd.inputs) {
	    if(isStone(in.spec.item) || (in.spec.constraint != null && isStone(in.spec.constraint)))
		return(true);
	    try {
		ItemInfo.Name nm = ItemInfo.find(ItemInfo.Name.class, in.spec.info());
		if(nm != null && isStoneName(nm.str.text))
		    return(true);
	    } catch(Loading l) {
	    } catch(RuntimeException e) {
	    }
	}
	return(false);
    }

    private static boolean isStone(ResData rd) {
	try {
	    Resource res = rd.res.get();
	    String base = res.basename();
	    return(base.equals("rockcrystal") || base.equals("stone") || Config.stoneItemBaseNames.contains(base));
	} catch(Loading l) {
	    return(false);
	}
    }

    private static boolean isStoneName(String name) {
	if(name == null)
	    return(false);
	String n = name.toLowerCase(Locale.ROOT);
	if(n.contains("stone") || n.contains("rock crystal"))
	    return(true);
	return(Config.stoneItemBaseNames.contains(n.replace(" ", "")));
    }
}
