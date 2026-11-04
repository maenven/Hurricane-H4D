/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.awt.Color;
import java.awt.Font;
import java.util.*;
import static haven.BuddyWnd.width;
import static haven.PType.*;

public abstract class Polity extends Widget {
    public final String cap, name;
    public int auth, acap, adrain, aseq;
    public boolean offline;
    public final Map<Integer, Member> memb = new HashMap<Integer, Member>();
    public int mseq;
    protected Widget mw;
    private int nextmemb = 0;

    public abstract String type();

    /* Dedupes the member-row draw-failure warning below so a row that fails
     * every single frame (the common case - the list keeps trying to redraw
     * it) doesn't flood the console/log into uselessness. Keyed by id+group
     * so a later, different failure on the same member still gets logged. */
    private static final Set<String> warnedBadRows = Collections.synchronizedSet(new HashSet<>());

	public static final Text unk = Text.render("Unknown (Not Memorised)", new Color(140, 140, 140));
	public static final Text self = Text.render(">> Yourself <<", new Color(255, 205, 0));
    public class Member {
	public final Integer id;
	public final int order;
	/** This member's permission group, off the wire, or -1 if the server
	 *  never sent one (this fork's Polity wire protocol was not observed
	 *  to send a group arg on "add" before this port - see
	 *  doc/H4D-features/group-permission-extension.md - so -1 may be the
	 *  only value ever seen until live-tested). */
	public int group = -1;

	public Member(Integer id) {
	    this.id = id;
	    this.order = nextmemb++;
	}

	public Member(Member p) {
	    this.id = p.id;
	    this.order = p.order;
	    this.group = p.group;
	}

	public Text rname() {
	    if(id == null)
		return(self);
	    BuddyWnd.Buddy b = getparent(GameUI.class).buddies.find(id);
	    return((b == null) ? unk : b.rname());
	}

	public void draw(GOut g) {
	    g.aimage(rname().tex(), UI.scale(0, 10), 0, 0.5);
	}

	public String name() {
	    return(rname().text);
	}

	private Text grouptag = null;
	private int grouptagGroup = Integer.MIN_VALUE;
	Text grouptag() {
	    if((grouptag == null) || (grouptagGroup != group)) {
		grouptag = Text.render("[" + group + "]");
		grouptagGroup = group;
	    }
	    return(grouptag);
	}
    }

    /* Member-list sort mode, remembered across sessions. */
    private static final String SORT_PREF = "polityMemberSortByGroup";

    public class MemberList extends SSearchBox<Member, Widget> {
	/** Every member, sorted by the current sort mode. */
	public List<Member> mlist = new ArrayList<>();
	/** mlist narrowed to groupFilter; what the list (and the name search) runs over. */
	private final List<Member> shown = new ArrayList<>();
	private int mseq;
	private boolean dirty = true;
	boolean sortByGroup = Utils.getprefb(SORT_PREF, false);
	/** Group to show, or null for every group. */
	Integer groupFilter = null;
	private MemberListControls controls = null;

	public MemberList(Coord sz) {
	    super(sz, UI.scale(20));
	}

	public MemberList(int w, int h) {
		this(Coord.of(w, (int)(h * 1.6) * UI.scale(20)));
	}

	public List<Member> allitems() {return(shown);}

	/* "#3" (or "#3 bob") matches members of group 3; anything else is the
	 * plain case-insensitive name search it always was. */
	public boolean searchmatch(Member m, String txt) {
	    String t = txt.trim().toLowerCase();
	    if(t.startsWith("#")) {
		String rest = t.substring(1);
		int sp = rest.indexOf(' ');
		String num = (sp < 0) ? rest : rest.substring(0, sp);
		String nm = (sp < 0) ? "" : rest.substring(sp + 1).trim();
		if(num.isEmpty())
		    return(m.name().toLowerCase().contains(nm));
		try {
		    if(Integer.parseInt(num) != m.group)
			return(false);
		} catch(NumberFormatException e) {
		    return(m.name().toLowerCase().contains(t));
		}
		return(m.name().toLowerCase().contains(nm));
	    }
	    return(m.name().toLowerCase().indexOf(txt.toLowerCase()) >= 0);
	}

	/** Groups at least one member is in, ascending. */
	List<Integer> presentGroups() {
	    TreeSet<Integer> ret = new TreeSet<>();
	    for(Member m : mlist)
		ret.add(m.group);
	    return(new ArrayList<>(ret));
	}

	int groupCount(int group) {
	    int n = 0;
	    for(Member m : mlist)
		if(m.group == group)
		    n++;
	    return(n);
	}

	void setSortByGroup(boolean v) {
	    sortByGroup = v;
	    Utils.setprefb(SORT_PREF, v);
	    dirty = true;
	}

	void setGroupFilter(Integer g) {
	    groupFilter = g;
	    dirty = true;
	}

	private void rebuild() {
	    mlist.clear();
	    mlist.addAll(memb.values());
	    Comparator<Member> byname = Comparator.comparing(Member::name).thenComparing(m -> m.order);
	    if(sortByGroup) {
		/* Unassigned members (-1) go last, not first. */
		Collections.sort(mlist, Comparator.<Member>comparingInt(m -> (m.group < 0) ? Integer.MAX_VALUE : m.group).thenComparing(byname));
	    } else {
		Collections.sort(mlist, byname);
	    }
	    if((groupFilter != null) && (groupCount(groupFilter) == 0))
		groupFilter = null;
	    shown.clear();
	    for(Member m : mlist) {
		if((groupFilter == null) || (m.group == groupFilter))
		    shown.add(m);
	    }
	    refilter();
	}

	public void tick(TickEvent ev) {
	    super.tick(ev);
	    if(controls == null && parent != null)
		installControls();
	    if(ev.visible && (dirty || (this.mseq != Polity.this.mseq))) {
		this.mseq = Polity.this.mseq;
		dirty = false;
		rebuild();
	    }
	}

	/* The village window itself is a server resource, so there's no
	 * client-side layout to slot a control row into. Instead, take one
	 * row off the top of this list (same total footprint, nothing in the
	 * resource's layout moves) and put the sort/filter controls there. */
	private void installControls() {
	    int barh = UI.scale(20) + UI.scale(2);
	    Coord at = this.c;
	    controls = parent.add(new MemberListControls(this, sz.x), at);
	    this.c = at.add(0, barh);
	    resize(Coord.of(sz.x, Math.max(sz.y - barh, UI.scale(20))));
	}

	public void destroy() {
	    if(controls != null) {
		controls.reqdestroy();
		controls = null;
	    }
	    super.destroy();
	}

	protected Widget makeitem(Member m, int idx, Coord sz) {
	    return(new ItemWidget<Member>(this, sz, m) {
		    protected boolean clicked(MouseDownEvent ev) {
			if((mw instanceof MemberWidget) && Utils.eq(((MemberWidget)mw).id, item.id))
			    list.change(null);
			else
			    list.change(item);
			return(true);
		    }

		    public void draw(GOut g) {
			try {
			    item.draw(g);
			} catch(Throwable t) {
			    /* A server-resource Member subclass (e.g. Village's
			     * VMember) can have its own internal fixed-size
			     * array keyed by group, independent of anything in
			     * this file - a group value outside what it expects
			     * (e.g. from before Village's range was corrected
			     * back to 0-7, or a value it never expected at all)
			     * throws here. Swallow it and skip the row instead
			     * of taking down the whole list/UI thread - see
			     * doc/H4D-features/group-permission-extension.md. */
			    g.chcolor();
			    if(warnedBadRows.add(item.id + "|" + item.group))
				new Warning(t, String.format("member row draw failed (id=%s, group=%d) - skipping row", item.id, item.group)).level(Warning.ERROR).issue();
			}
			/* Drawn here, in the wrapper, not inside Member.draw()
			 * itself, so it always runs regardless of anything a
			 * future Member subclass's own draw() override does. */
			if(item.group >= 0) {
			    g.chcolor(210, 210, 210, 255);
			    g.aimage(item.grouptag().tex(), Coord.of(g.sz().x - UI.scale(5), UI.scale(10)), 1.0, 0.5);
			    g.chcolor();
			}
		    }
		});
	}

	protected void drawbg(GOut g) {
	    g.chcolor(0, 0, 0, 128);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	}

	protected void drawslot(GOut g, Member m, int idx, Area area) {
	    if((mw instanceof MemberWidget) && (m != null) && Utils.eq(((MemberWidget)mw).id, m.id))
		drawsel(g, m, idx, area);
	}

	public void change(Member pm) {
	    if(pm == null)
		Polity.this.wdgmsg("sel");
	    else
		Polity.this.wdgmsg("sel", pm.id);
	}
    }

    private static final int ALLGROUPS = Integer.MIN_VALUE;

    /** Sort toggle + group filter shown above the member list (H4D). */
    class MemberListControls extends Widget {
	final MemberList list;
	final GroupFilterBox filter;

	MemberListControls(MemberList list, int w) {
	    super(Coord.of(w, UI.scale(20)));
	    this.list = list;
	    int bw = UI.scale(95), gap = UI.scale(4);
	    add(new SortButton(Coord.of(bw, sz.y)), 0, 0);
	    filter = add(new GroupFilterBox(w - bw - gap), bw + gap, 0);
	}

	public void tick(double dt) {
	    super.tick(dt);
	    int want = (list.groupFilter == null) ? ALLGROUPS : list.groupFilter;
	    if((filter.sel == null) || (filter.sel != want))
		filter.sel = want;
	}

	private class SortButton extends Widget {
	    SortButton(Coord sz) {super(sz);}

	    public void draw(GOut g) {
		g.chcolor(new Color(50, 50, 50));
		g.frect(Coord.z, sz);
		g.chcolor(new Color(110, 110, 110));
		g.rect(Coord.z, sz);
		g.chcolor(Color.WHITE);
		g.atext(list.sortByGroup ? "Sort: Group" : "Sort: Name", sz.div(2), 0.5, 0.5);
		g.chcolor();
	    }

	    public boolean mousedown(MouseDownEvent ev) {
		if(ev.b == 1) {
		    list.setSortByGroup(!list.sortByGroup);
		    return(true);
		}
		return(super.mousedown(ev));
	    }

	    public Object tooltip(Coord c, Widget prev) {
		return("Click to sort members by " + (list.sortByGroup ? "name" : "group") + ".\nTip: type #3 in the list to search group 3.");
	    }
	}

	class GroupFilterBox extends OldDropBox<Integer> {
	    private List<Integer> items = Collections.singletonList(ALLGROUPS);

	    GroupFilterBox(int w) {
		super(w, 12, UI.scale(20));
		sel = ALLGROUPS;
	    }

	    public void tick(double dt) {
		super.tick(dt);
		List<Integer> n = new ArrayList<>();
		n.add(ALLGROUPS);
		n.addAll(list.presentGroups());
		items = n;
	    }

	    protected Integer listitem(int i) {return(items.get(i));}
	    protected int listitems() {return(items.size());}

	    private String label(int group) {
		if(group == ALLGROUPS)
		    return("All groups (" + list.mlist.size() + ")");
		if(group < 0)
		    return("No group (" + list.groupCount(group) + ")");
		String lbl = haven.groups.GroupLabels.get(haven.groups.GroupLabels.Scope.VILLAGE, name, group);
		return(group + (lbl.isEmpty() ? "" : (" - " + lbl)) + " (" + list.groupCount(group) + ")");
	    }

	    protected void drawitem(GOut g, Integer item, int i) {
		int sw = itemh - UI.scale(6);
		int x = UI.scale(3);
		if(item >= 0) {
		    g.chcolor(BuddyWnd.gcolor(item));
		    g.frect(Coord.of(x, (itemh - sw) / 2), Coord.of(sw, sw));
		    x += sw + UI.scale(4);
		}
		g.chcolor(Color.WHITE);
		g.text(label(item), Coord.of(x, (itemh - Text.std.height()) / 2));
		g.chcolor();
	    }

	    public void change(Integer item) {
		super.change(item);
		if(item != null)
		    list.setGroupFilter((item == ALLGROUPS) ? null : item);
	    }

	    public Object tooltip(Coord c, Widget prev) {
		return("Show only members of one group");
	    }
	}
    }

    public static abstract class MemberWidget extends Widget {
	public final Integer id;

	public MemberWidget(Coord sz, Integer id) {
	    super(sz);
	    this.id = id;
	}
    }

    public static final Text.Foundry nmf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(14))).aa(true);
    public static final Text.Foundry membf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12))).aa(true);

    public Polity(String cap, String name) {
	super(new Coord(width, UI.scale(200)));
	this.cap = cap;
	this.name = name;
    }

    public class AuthMeter extends Widget {
	public AuthMeter(Coord sz) {
	    super(sz);
	}

	private int aseq = -1;
	private Tex rauth = null;
	public void draw(GOut g) {
	    synchronized(Polity.this) {
		g.chcolor(0, 0, 0, 255);
		g.frect(new Coord(0, 0), new Coord(sz.x, sz.y));
		g.chcolor(128, 0, 0, 255);
		int mw = (int)(((sz.x - 2) * (long)auth) / ((acap == 0) ? 1 : acap));
		g.frect(new Coord(1, 1), new Coord(mw, sz.y - UI.scale(2)));
		g.chcolor();
		if((rauth != null) && (aseq != Polity.this.aseq)) {
		    rauth.dispose();
		    rauth = null;
		    aseq = Polity.this.aseq;
		}
		if(rauth == null) {
		    Color col = offline ? Color.RED : Color.WHITE;
		    rauth = new TexI(Utils.outline2(Text.render(String.format("%,d / %,d", auth, acap), col).img, Utils.contrast(col)));
		}
		g.aimage(rauth, sz.div(2), 0.5, 0.5);
	    }
	}

	public Object tooltip(Coord c, Widget prev) {
	    if(adrain > 0)
		return(String.format("Drain: %,d/day", adrain));
	    return(null);
	}
    }

    protected Member parsememb(Object[] args, Member p) {
	if(p == null) {
	    Integer id = INT.of(args[0]);
	    return(new Member(id));
	} else {
	    return(new Member(p));
	}
    }

    public void add(Member pm) {
	synchronized(this) {
	    memb.put(pm.id, pm);
	    mseq++;
	}
    }

    public void remove(Member pm) {
	synchronized(this) {
	    memb.remove(pm.id);
	    mseq++;
	}
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "auth") {
	    synchronized(this) {
		auth = INT.of(args[0]);
		acap = INT.of(args[1]);
		adrain = INT.of(args[2]);
		offline = BOOL.of(args[3]);
		aseq++;
	    }
	} else if(msg == "add") {
	    Integer id = INT.of(args[0]);
	    synchronized(this) {
		Member pm = parsememb(args, memb.get(id));
		/* args[1] confirmed live to be the member's real permission
		 * group - watched it track a member's group through five live
		 * reassignments (see doc/H4D-features/group-permission-extension.md).
		 * args[2] is something else (stayed constant while args[1]
		 * changed each time); left unread since nothing here needs it.
		 * Set here, not inside parsememb(), because parsememb() is
		 * overridable and Village's own override (which must exist,
		 * to build its VMember subclass) doesn't run this - confirmed
		 * live: item.group stayed -1 for every Village member despite
		 * Polity.uimsg() itself definitely receiving the real value.
		 * Applying it to the object parsememb() returns, whichever
		 * override built it, is what actually executes. */
		if(args.length > 1)
		    pm.group = INT.of(args[1]);
		add(pm);
	    }
	} else if(msg == "rm") {
	    Integer id = INT.of(args[0]);
	    synchronized(this) {
		remove(memb.get(id));
	    }
	} else {
	    super.uimsg(msg, args);
	}
    }

    public void addchild(Widget child, Object... args) {
	if(args[0] instanceof String) {
	    String p = (String)args[0];
	    if(p.equals("m")) {
		mw = child;
		add(child, 0, UI.scale(210));
		pack();
		return;
	    }
	}
	super.addchild(child, args);
    }

    public void cdestroy(Widget w) {
	if(w == mw) {
	    mw = null;
	    pack();
	}
    }
}
