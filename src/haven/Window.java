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

import haven.automated.InventorySorter;
import haven.automated.StackAllItems;
import haven.automated.UnstackAllItems;
import haven.automated.mapper.MappingClient;
import haven.render.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

import static haven.PUtils.*;

public class Window extends Widget {
    public static final Pipe.Op bgblend = FragColor.blend.nil;
    public static final Pipe.Op cblend  = FragColor.blend(new BlendMode(BlendMode.Function.ADD, BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
									BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA));
    public static final Tex bg = Resource.loadtex("gfx/hud/wnd/lg/bg");
    public static final Tex bgl = Resource.loadtex("gfx/hud/wnd/lg/bgl");
    public static final Tex bgr = Resource.loadtex("gfx/hud/wnd/lg/bgr");
    public static final Tex cl = Resource.loadtex("gfx/hud/wnd/lg/cl");
    public static final TexI cm = new TexI(Resource.loadsimg("gfx/hud/wnd/lg/cm"));
    public static final Tex cr = Resource.loadtex("gfx/hud/wnd/lg/cr");
    public static final Tex tm = Resource.loadtex("gfx/hud/wnd/lg/tm");
    public static final Tex tr = Resource.loadtex("gfx/hud/wnd/lg/tr");
    public static final Tex lm = Resource.loadtex("gfx/hud/wnd/lg/lm");
    public static final Tex lb = Resource.loadtex("gfx/hud/wnd/lg/lb");
    public static final Tex rm = Resource.loadtex("gfx/hud/wnd/lg/rm");
    public static final Tex bl = Resource.loadtex("gfx/hud/wnd/lg/bl");
    public static final Tex bm = Resource.loadtex("gfx/hud/wnd/lg/bm");
    public static final Tex br = Resource.loadtex("gfx/hud/wnd/lg/br");
    public static final Tex sizer = Resource.loadtex("gfx/hud/wnd/sizer");
    public static final Coord tlm = UI.scale(18, 30);
    public static final Coord brm = UI.scale(13, 22);
	public static final Coord cpo = UI.rscale(27, 6); // ND: This is the location of the window title text
    public static final int capo = 7, capio = 2;
    public static final Coord dlmrgn = UI.scale(10, 10); // ND: Margin set for LARGE windows, between the content and window edge
    public static final Coord dsmrgn = UI.scale(4, 4); // ND: margin set for SMALL windows, between the content and window edge
    public static final BufferedImage ctex = Resource.loadsimg("gfx/hud/fonttex");
    public static final BufferedImage ctexUnfocused = Resource.loadsimg("gfx/hud/fonttexUnfocused");
    @Deprecated public static final Text.Furnace cf = DefaultDeco.cf;
    @Deprecated public static final Text.Furnace ncf = DefaultDeco.ncf;
    public static final IBox wbox = new IBox.Scaled("gfx/hud/wnd", "tl", "tr", "bl", "br", "extvl", "extvr", "extht", "exthb") {
	    final Coord co = UI.scale(3, 3), bo = UI.scale(2, 2);

	    public Coord btloff() {return(super.btloff().sub(bo));}
	    public Coord ctloff() {return(super.ctloff().sub(co));}
	    public Coord bbroff() {return(super.bbroff().sub(bo));}
	    public Coord cbroff() {return(super.cbroff().sub(co));}
	    public Coord bisz() {return(super.bisz().sub(bo.mul(2)));}
	    public Coord cisz() {return(super.cisz().sub(co.mul(2)));}
	};
    private static final BufferedImage[] cbtni = new BufferedImage[] {
	Resource.loadsimg("gfx/hud/wnd/lg/cbtnu"),
	Resource.loadsimg("gfx/hud/wnd/lg/cbtnd"),
	Resource.loadsimg("gfx/hud/wnd/lg/cbtnh")};
    private static final BufferedImage[] stackbtni = new BufferedImage[] {
    Resource.loadsimg("gfx/hud/wnd/lg/stackbtnu"),
    Resource.loadsimg("gfx/hud/wnd/lg/stackbtnd"),
    Resource.loadsimg("gfx/hud/wnd/lg/stackbtnh")};
    private static final BufferedImage[] unstackbtni = new BufferedImage[] {
    Resource.loadsimg("gfx/hud/wnd/lg/unstackbtnu"),
    Resource.loadsimg("gfx/hud/wnd/lg/unstackbtnd"),
    Resource.loadsimg("gfx/hud/wnd/lg/unstackbtnh")};
    private static final BufferedImage[] sortbtni = new BufferedImage[] {
    Resource.loadsimg("gfx/hud/wnd/lg/sortbtnu"),
    Resource.loadsimg("gfx/hud/wnd/lg/sortbtnd"),
    Resource.loadsimg("gfx/hud/wnd/lg/sortbtnh")};
	private static final BufferedImage[] extlistbtni = new BufferedImage[] {
    Resource.loadsimg("gfx/hud/wnd/lg/qlistbtnu"),
    Resource.loadsimg("gfx/hud/wnd/lg/qlistbtnd"),
    Resource.loadsimg("gfx/hud/wnd/lg/qlistbtnh")};
    public Deco deco;
    public String cap;
    public TexRaw gbuf = null;
    private FragColor gout;
    private Pipe.Op gbasic;
    public UI.Grab dm = null;
    private Coord doff;
    public boolean large = false;
    private String lockcap = null;
    private boolean lockv = false;

    /* Window lock: a locked window can't be dragged or resized. The state is
     * saved per window title, so e.g. every "Inventory" window stays locked. */
    public boolean locked() {
	if(cap != null && !cap.equals(lockcap)) {
	    lockcap = cap;
	    lockv = Utils.getprefb("wndlock-" + cap, false);
	}
	return(lockv);
    }

    public void setlocked(boolean v) {
	lockv = v;
	if(cap != null) {
	    lockcap = cap;
	    Utils.setprefb("wndlock-" + cap, v);
	}
	if(v && (dm != null)) {
	    dm.remove();
	    dm = null;
	}
    }

    public static class LockButton extends Widget {
	private static final Map<String, Tex> cache = new HashMap<>();
	private boolean h = false, a = false;
	private UI.Grab d = null;

	public LockButton(Coord sz) {
	    super(sz);
	}

	private Window wnd() {
	    for(Widget w = parent; w != null; w = w.parent) {
		if(w instanceof Window)
		    return((Window)w);
	    }
	    return(null);
	}

	private final Object locktip = new KeyboundTip("Unlock window (allow moving)", false);
	private final Object unlocktip = new KeyboundTip("Lock window in place", false);

	/* Built from the Stack All button art (same frame, size and placement as the
	 * other title bar buttons) with the stack glyph replaced by a padlock. */
	private static Tex img(int state, boolean locked) {
	    String key = state + "/" + locked;
	    synchronized(cache) {
		Tex tex = cache.get(key);
		if(tex == null) {
		    tex = new TexI(padlock(stackbtni[state], locked));
		    cache.put(key, tex);
		}
		return(tex);
	    }
	}

	private static int lum(int argb) {
	    return((((argb >> 16) & 0xff) * 3 + ((argb >> 8) & 0xff) * 6 + (argb & 0xff)) / 10);
	}

	private static BufferedImage padlock(BufferedImage src, boolean locked) {
	    int w = src.getWidth(), h = src.getHeight();
	    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
	    java.awt.Graphics2D g = img.createGraphics();
	    g.drawImage(src, 0, 0, null);
	    int x0 = w, y0 = h, x1 = -1, y1 = -1;
	    for(int y = 0; y < h; y++) {
		for(int x = 0; x < w; x++) {
		    if((img.getRGB(x, y) >>> 24) >= 128) {
			x0 = Math.min(x0, x); x1 = Math.max(x1, x);
			y0 = Math.min(y0, y); y1 = Math.max(y1, y);
		    }
		}
	    }
	    if(x1 < 0) {
		g.dispose();
		return(img);
	    }
	    double cx = (x0 + x1 + 1) / 2.0, cy = (y0 + y1 + 1) / 2.0, r = (x1 - x0 + 1) / 2.0;
	    /* Fill colour of the button face: the middle value of the pixels inside
	     * the frame that aren't part of the white glyph or its dark outline. */
	    double fr = r * 0.78;
	    java.util.List<Integer> face = new java.util.ArrayList<>();
	    for(int y = 0; y < h; y++) {
		for(int x = 0; x < w; x++) {
		    double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
		    if(dx * dx + dy * dy <= fr * fr) {
			int c = img.getRGB(x, y), l = lum(c);
			if(((c >>> 24) >= 128) && (l > 70) && (l < 200))
			    face.add(c);
		    }
		}
	    }
	    if(!face.isEmpty()) {
		face.sort((p, q) -> lum(p) - lum(q));
		int fill = face.get(face.size() / 2);
		for(int y = 0; y < h; y++) {
		    for(int x = 0; x < w; x++) {
			double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
			if(dx * dx + dy * dy <= fr * fr) {
			    int l = lum(img.getRGB(x, y));
			    if((l <= 95) || (l >= 175))
				img.setRGB(x, y, fill);
			}
		    }
		}
	    }
	    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
	    float lw = (float)Math.max(1.5, r * 0.14), ow = lw + (float)Math.max(1.5, r * 0.11);
	    double bw = r * 0.74, bh = r * 0.52;
	    double bx = cx - bw / 2, by = cy + r * 0.02 + (locked ? 0 : r * 0.08);
	    double sw = bw * 0.62, sh = r * 0.62;
	    double sx = cx - sw / 2, sy = by - sh / 2 - r * 0.12 - (locked ? 0 : r * 0.24);
	    double legb = by + bh * 0.2;
	    java.awt.geom.Path2D shackle = new java.awt.geom.Path2D.Double();
	    shackle.moveTo(sx, locked ? legb : sy + sh / 2 + r * 0.06);
	    shackle.lineTo(sx, sy + sh / 2);
	    shackle.append(new java.awt.geom.Arc2D.Double(sx, sy, sw, sh, 180, -180, java.awt.geom.Arc2D.OPEN), true);
	    shackle.lineTo(sx + sw, legb);
	    java.awt.Shape body = new java.awt.geom.RoundRectangle2D.Double(bx, by, bw, bh, r * 0.18, r * 0.18);
	    Color dark = new Color(40, 40, 40), white = new Color(245, 245, 245);
	    g.setColor(dark);
	    g.setStroke(new java.awt.BasicStroke(ow, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
	    g.draw(shackle);
	    g.draw(body);
	    g.fill(body);
	    g.setColor(white);
	    g.setStroke(new java.awt.BasicStroke(lw, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
	    g.draw(shackle);
	    g.fill(body);
	    g.setColor(dark);
	    double kr = r * 0.11;
	    g.fill(new java.awt.geom.Ellipse2D.Double(cx - kr, by + bh * 0.28, kr * 2, kr * 2));
	    g.fill(new java.awt.geom.Rectangle2D.Double(cx - kr * 0.5, by + bh * 0.28 + kr, kr, bh * 0.32));
	    g.dispose();
	    return(img);
	}

	public void draw(GOut g) {
	    Window wnd = wnd();
	    boolean locked = (wnd != null) && wnd.locked();
	    int state = (a && h) ? 1 : ((h || (d != null)) ? 2 : 0);
	    g.image(img(state, locked), Coord.z);
	}

	/* Only the visible button counts, like IButton (the art has a transparent
	 * area above the title bar). */
	private boolean hit(Coord c) {
	    BufferedImage up = stackbtni[0];
	    if(!c.isect(Coord.z, sz) || (c.x >= up.getWidth()) || (c.y >= up.getHeight()))
		return(false);
	    if(up.getRaster().getNumBands() < 4)
		return(true);
	    return(up.getRaster().getSample(c.x, c.y, 3) >= 128);
	}

	public boolean checkhit(Coord c) {
	    return(hit(c));
	}

	public void mousemove(MouseMoveEvent ev) {
	    h = hit(ev.c);
	    super.mousemove(ev);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    if((ev.b != 1) || !hit(ev.c))
		return(false);
	    a = true;
	    d = ui.grabmouse(this);
	    return(true);
	}

	public boolean mouseup(MouseUpEvent ev) {
	    if((d != null) && (ev.b == 1)) {
		d.remove();
		d = null;
		boolean click = a && hit(ev.c);
		a = false;
		if(click) {
		    Window wnd = wnd();
		    if(wnd != null)
			wnd.setlocked(!wnd.locked());
		}
		return(true);
	    }
	    return(super.mouseup(ev));
	}

	public Object tooltip(Coord c, Widget prev) {
	    Window wnd = wnd();
	    if((wnd != null) && wnd.locked())
		return(locktip);
	    return(unlocktip);
	}
    }

    @RName("wnd")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Coord sz = UI.scale((Coord)args[0]);
	    String cap = (args.length > 1) ? (String)args[1] : null;
	    boolean lg = (args.length > 2) ? Utils.bv(args[2]) : false;
	    if("Prospecting".equals(cap))
		return(new ProspectingWnd(sz, cap, lg));
	    return(new Window(sz, cap, lg));
	}
    }

    private Window(Coord sz, String cap, boolean lg, Deco deco, boolean defdeco) {
	super(sz);
	this.cap = cap;
	this.large = lg;
	setfocustab(true);
	chdeco(defdeco ? makedeco() : deco);
    }

    public Window(Coord sz, String cap, boolean lg, Deco deco) {
	this(sz, cap, lg, deco, false);
    }

    public Window(Coord sz, String cap, boolean lg) {
	this(sz, cap, lg, null, true);
    }

    public Window(Coord sz, String cap) {
	this(sz, cap, false);
    }

    protected Deco makedeco() {
	return(new DefaultDeco(this.large));
    }

    protected void added() {
	super.added();
	if(visible())
	    parent.setfocus(this);
	initanim();
     if (OptWnd.stackWindowsWhenOpenedCheckBox.a) {
         if (this.cap != null) {
             Coord loc = Utils.getprefc("wndc-" + this.cap, Coord.z);
             if (loc != Coord.z) // ND: Should default to whatever loftar spawns them at
                 this.c = loc;
         }
     }

    if (deco instanceof DefaultDeco) {
        ((DefaultDeco) deco).refreshInventoryButtons();
    }

    }

    public void chcap(String cap) {
	this.cap = cap;
	attachLocalExtInventory(getchild(Inventory.class));
	if(deco instanceof DefaultDeco)
	    ((DefaultDeco) deco).refreshInventoryButtons();
    }

    private boolean shouldHaveInventoryButtons() {
	return cap != null && Arrays.stream(Config.EXCLUDED_INVENTORY_WINDOWS).noneMatch(cap::equals);
    }

    public void chdeco(Deco deco) {
	Coord psz, poff;
	if(this.deco != null) {
	    Area ca = this.deco.contarea();
	    psz = ca.sz();
	    poff = ca.ul;
	    this.deco.reqdestroy();
	    this.deco = null;
	} else {
	    psz = this.sz;
	    poff = Coord.z;
	}
	if(deco != null)
	    this.deco = add(deco);
	resize2(psz);
	Coord coff = poff.sub(ca().ul);
	this.c = this.c.add(coff);
	if(dm != null)
	    this.doff = this.doff.sub(coff);
    }

    public static abstract class Deco extends Widget {
	public Deco() {
	    z(-100);
	}

	public abstract void iresize(Coord isz);
	public abstract Area contarea();
    }

    public abstract static class DragDeco extends Deco {
	public boolean mousedown(MouseDownEvent ev) {
	    if(ev.propagate(this))
		return(true);
	    if(checkhit(ev.c)) {
		Window wnd = (Window)parent;
		wnd.parent.setfocus(wnd);
		wnd.raise();
		if(ev.b == 1)
		    wnd.drag(ev.c);
		return(true);
	    }
	    return(super.mousedown(ev));
	}
    }

    public static class DefaultDeco extends DragDeco {
	public static final Text.Forge cf =  new PUtils.BlurFurn(new PUtils.TexFurn(new Text.Foundry(Text.fraktur, 15).aa(true), ctex),
								   UI.rscale(0.75), UI.rscale(1.0), Color.BLACK);
	public static final Text.Forge ncf = new PUtils.BlurFurn(new PUtils.TexFurn(new Text.Foundry(Text.fraktur, 15).aa(true), ctexUnfocused),
								   UI.rscale(0.75), UI.rscale(1.0), Color.BLACK);
	public final boolean lg;
	public final IButton cbtn;
    public IButton extlistbtn, stackbtn, unstackbtn, sortbtn;
	public final LockButton lockbtn;
	public boolean dragsize, cfocus;
	public Area aa, ca;
	public Coord cptl = Coord.z, cpsz = Coord.z;
	public Coord wsz; // ND: Added this to be able to make the window top bar fully draggable
	public int cmw;
	public Text cap = null;

	public DefaultDeco(boolean lg) {
	    this.lg = lg;
	    cbtn = add(new IButton(cbtni[0], cbtni[1], cbtni[2])).action(() -> ((Window)parent).reqclose());
	    lockbtn = add(new LockButton(Utils.imgsz(stackbtni[0])));
	}

	public void tick(double dt) {
	    super.tick(dt);
	    // Keep the lock button just left of the leftmost visible title bar button.
	    int left = cbtn.c.x;
	    for(IButton b : new IButton[] {extlistbtn, sortbtn, unstackbtn, stackbtn}) {
		if((b != null) && b.visible && (b.c.x < left))
		    left = b.c.x;
	    }
	    Coord nc = Coord.of(left - UI.scale(20), cbtn.c.y);
	    if(!nc.equals(lockbtn.c))
		lockbtn.c = nc;
	}
	public DefaultDeco() {this(false);}

	public DefaultDeco dragsize(boolean v) {
	    this.dragsize = v;
	    return(this);
	}

	public void iresize(Coord isz) {
	    Coord mrgn = lg ? dlmrgn : dsmrgn;
	    Coord asz = isz;
	    Coord csz = asz.add(mrgn.mul(2));
	    wsz = csz.add(tlm).add(brm); // ND: This is now a variable outside of this function to make the window top bar fully draggable
	    resize(wsz);
	    ca = Area.sized(tlm, csz);
	    aa = Area.sized(ca.ul.add(mrgn), asz);
//		int extra = inventoryExtraWidth();
		int anchor = sz.x;
		cbtn.c = Coord.of(anchor - cbtn.sz.x - UI.scale(9), -UI.scale(10));
		if (extlistbtn != null)
			extlistbtn.c = Coord.of(anchor - cbtn.sz.x - UI.scale(29), -UI.scale(10));
        if (sortbtn != null)
            sortbtn.c = Coord.of(anchor - cbtn.sz.x - UI.scale(49), - UI.scale(10));
        if (unstackbtn != null)
            unstackbtn.c = Coord.of(anchor - cbtn.sz.x - UI.scale(69), -UI.scale(10));
        if (stackbtn != null)
            stackbtn.c = Coord.of(anchor - cbtn.sz.x - UI.scale(88), -UI.scale(10));
		cpsz = Coord.of((int)(wsz.x*0.95), cm.sz().y).sub(cptl); // ND: changed this to make the window top bar fully draggable WHEN RESIZED (for instance, buddy window)
	}

	public Area contarea() {
	    return(aa);
	}

	protected void cdraw(GOut g) {
	    ((Window)parent).cdraw(g);
	}

	protected void drawbg(GOut g) {
	    g.usestate(bgblend);
	    Coord bgc = new Coord();
	    for(bgc.y = ca.ul.y; bgc.y < ca.br.y; bgc.y += bg.sz().y) {
		for(bgc.x = ca.ul.x; bgc.x < ca.br.x; bgc.x += bg.sz().x)
		    g.image(bg, bgc, ca.ul, ca.br);
	    }
	    g.defstate();
	    bgc.x = ca.ul.x;
//	    for(bgc.y = ca.ul.y; bgc.y < ca.br.y; bgc.y += bgl.sz().y)
//		g.image(bgl, bgc, ca.ul, ca.br);
//	    bgc.x = ca.br.x - bgr.sz().x;
//	    for(bgc.y = ca.ul.y; bgc.y < ca.br.y; bgc.y += bgr.sz().y)
//		g.image(bgr, bgc, ca.ul, ca.br);
	}

	protected void drawframe(GOut g) {
	    Window wnd = (Window)parent;
	    if((cap == null) || (cap.text != wnd.cap) || (cfocus != wnd.hasfocus)) {
		cap = (wnd.cap == null) ? null : ((cfocus = wnd.hasfocus) ? cf : ncf).render(wnd.cap);
		cmw = (cap == null) ? 0 : cap.sz().x;
		cmw = Math.max(cmw, this.sz.x / 4);
		cptl = Coord.of(ca.ul.x, 0);
		cpsz = Coord.of((int)(wsz.x*0.95), cm.sz().y).sub(cptl); // ND: changed this to make the window top bar fully draggable
		cmw = cmw - (cl.sz().x - cpo.x) - UI.scale(5);
	    }
	    if(dragsize)
		g.image(sizer, ca.br.sub(sizer.sz()));
	    Coord mdo, cbr;
	    g.image(cl, Coord.z);
	    mdo = Coord.of(cl.sz().x, 0);
	    cbr = mdo.add(cmw, cm.sz().y);
	    for(int x = 0; x < cmw; x += cm.sz().x)
		g.image(cm, mdo.add(x, 0), Coord.z, cbr);
	    g.image(cr, Coord.of(cl.sz().x + cmw, 0));
	    g.image(cap.tex(), cpo);
	    mdo = Coord.of(cl.sz().x + cmw + cr.sz().x, 0);
	    cbr = Coord.of(sz.x - tr.sz().x, tm.sz().y);
	    for(; mdo.x < cbr.x; mdo.x += tm.sz().x)
		g.image(tm, mdo, Coord.z, cbr);
	    g.image(tr, Coord.of(sz.x - tr.sz().x, 0));

	    mdo = Coord.of(0, cl.sz().y);
	    cbr = Coord.of(lm.sz().x, sz.y - bl.sz().y);
	    if(cbr.y - mdo.y >= lb.sz().y) {
		cbr.y -= lb.sz().y;
		g.image(lb, Coord.of(0, cbr.y));
	    }
	    for(; mdo.y < cbr.y; mdo.y += lm.sz().y)
		g.image(lm, mdo, Coord.z, cbr);

	    mdo = Coord.of(sz.x - rm.sz().x, tr.sz().y);
	    cbr = Coord.of(sz.x, sz.y - br.sz().y);
	    for(; mdo.y < cbr.y; mdo.y += rm.sz().y)
		g.image(rm, mdo, Coord.z, cbr);

	    g.image(bl, Coord.of(0, sz.y - bl.sz().y));
	    mdo = Coord.of(bl.sz().x, sz.y - bm.sz().y);
	    cbr = Coord.of(sz.x - br.sz().x, sz.y);
	    for(; mdo.x < cbr.x; mdo.x += bm.sz().x)
		g.image(bm, mdo, Coord.z, cbr);
	    g.image(br, sz.sub(br.sz()));
	}

	public void draw(GOut g) {
	    drawbg(g);
	    cdraw(g.reclip(aa.ul, aa.sz()));
	    drawframe(g);
	    super.draw(g);
	}

	public UI.Grab szdrag;
	private Coord szdragc;
	public boolean mousedown(MouseDownEvent ev) {
	    if(dragsize && !((Window)parent).locked()) {
		Coord c = ev.c, cc = c.sub(ca.ul);
		if((ev.b == 1) && (c.x < ca.br.x) && (c.y < ca.br.y) && (c.y >= ca.br.y - UI.scale(25) + (ca.br.x - c.x))) {
		    szdrag = ui.grabmouse(this);
		    szdragc = aa.sz().sub(c);
		    return(true);
		}
	    }
	    return(super.mousedown(ev));
	}

	public void mousemove(MouseMoveEvent ev) {
	    if(szdrag != null)
		((Window)parent).resize(ev.c.add(szdragc));
	    super.mousemove(ev);
	}

	public boolean mouseup(MouseUpEvent ev) {
	    if((ev.b == 1) && (szdrag != null)) {
		szdrag.remove();
		szdrag = null;
		return(true);
	    }
	    return(super.mouseup(ev));
	}

	public boolean checkhit(Coord c) {
	    Coord cpc = c.sub(cptl);
	    return(ca.contains(c) || (c.isect(cptl, cpsz) && (cm.back.getRaster().getSample(cpc.x % cm.back.getWidth(), cpc.y, 3) >= 128)));
	}

	private Inventory findInventory() {
		if (!(parent instanceof Window))
			return null;

		Window wnd = (Window) parent;
		for (Widget w = wnd.child; w != null; w = w.next) {
			if (w == wnd.deco)
				continue;
            Inventory inv = Inventory.fromWidget(w);
            if (inv != null)
                return inv;
		}
		return null;
	}

	private ExtInventory findExtInventory() {
		if (!(parent instanceof Window))
			return null;

		Window wnd = (Window) parent;
		for (Widget w = wnd.child; w != null; w = w.next) {
			if (w == wnd.deco)
				continue;
			if (w instanceof ExtInventory)
				return (ExtInventory) w;
		}
		return null;
	}

	private int inventoryExtraWidth() {
		if (!(parent instanceof Window))
			return 0;

		Window wnd = (Window) parent;
		for (Widget w = wnd.child; w != null; w = w.next) {
			if (w == wnd.deco)
				continue;
			if (w instanceof ExtInventory) {
				ExtInventory ext = (ExtInventory) w;
				return Math.max(0, ext.sz.x - ext.inv.sz.x);
			}
		}
		return 0;
	}

	private boolean shouldShowInventoryButtons() {
		return (parent instanceof Window) && ((Window) parent).shouldHaveInventoryButtons();
	}

	private void refreshInventoryButtons() {
		boolean visible = shouldShowInventoryButtons();
		boolean hasInventory = findInventory() != null;
		boolean hasExtInventory = findExtInventory() != null;

		if (stackbtn != null)
			stackbtn.visible = visible && hasInventory;
		if (unstackbtn != null)
			unstackbtn.visible = visible && hasInventory;
		if (sortbtn != null)
			sortbtn.visible = visible && hasInventory;
		if (extlistbtn != null)
			extlistbtn.visible = visible && hasExtInventory;
	}

	public void addExtListBtn() {
		if (extlistbtn != null)
			return;

		extlistbtn = add(new IButton(extlistbtni[0], extlistbtni[1], extlistbtni[2])).action(() -> {
			ExtInventory ext = findExtInventory();
			if (ext != null) {
				ext.togglePanel();
			}
		});
		extlistbtn.settip("Extended View");
		extlistbtn.visible = false;
		refreshInventoryButtons();
	}

    public void addStackBtn() {
		if (stackbtn != null)
			return;

		stackbtn = add(new IButton(stackbtni[0], stackbtni[1], stackbtni[2])).action(() -> {
			Inventory inv = findInventory();
			if (inv != null) {
				new Thread(new StackAllItems(this.ui.gui, inv)).start();
			}
		});
		stackbtn.settip("Stack All");
		stackbtn.visible = false;
		refreshInventoryButtons();
	}

    public void addSortBtn() {
		if (sortbtn != null)
			return;

        sortbtn = add(new IButton(sortbtni[0], sortbtni[1], sortbtni[2])).action(() -> {
            for (Widget wdg = this; wdg != null; wdg = wdg.next) {
                Inventory inv = Inventory.fromWidget(wdg);
                if (inv != null) {
                    InventorySorter.sort(inv);
                    break;
                }
            }
        });
        sortbtn.settip("Sort All");
        sortbtn.visible = false;
        refreshInventoryButtons();
    }

    public void addUnstackBtn() {
		if (unstackbtn != null)
			return;

		unstackbtn = add(new IButton(unstackbtni[0], unstackbtni[1], unstackbtni[2])).action(() -> {
			Inventory inv = findInventory();
			if (inv != null) {
				new Thread(new UnstackAllItems(this.ui.gui, inv)).start();
			}
		});
		unstackbtn.settip("Unstack All");
		unstackbtn.visible = false;
		refreshInventoryButtons();
	}

    }

    public void cdraw(GOut g) {
    }

    public Pipe.Op gbasic() {
	if((gbuf == null) || !Utils.eq(sz, gbuf.back.tex.sz())) {
	    if(gbuf != null)
		gbuf.dispose();
	    gbuf = new TexRaw(new Texture2D.Sampler2D(new Texture2D(this.sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null)), true);
	    gbuf.back.minfilter(Texture.Filter.LINEAR).magfilter(Texture.Filter.LINEAR);
	    gout = new FragColor<>(gbuf.back.tex.image(0));
	    Area garea = Area.sized(this.sz);
	    gbasic = Pipe.Op.compose(gout, DepthBuffer.slot.nil, cblend,
				     new States.Viewport(garea), new Ortho2D(garea));
	}
	return(gbasic);
    }

    protected void drawbuf(GOut g) {
	super.draw(g);
    }

    protected void drawfin(GOut g, Tex buf) {
	if(anim != null)
	    anim.draw(g, buf);
	else
	    g.image(buf, Coord.z);
    }

    public void draw(GOut og) {
	if(animst != "dest") {
	    GOut g = new GOut(og.out, og.basicstate().prep(gbasic()), this.sz);
	    g.out.clear(g.state(), FragColor.fragcol, FColor.BLACK_T);
	    drawbuf(g);
	}
	if(gbuf != null)
	    drawfin(og, gbuf);
    }

    public Coord contentsz() {
	Coord max = new Coord(0, 0);
	for(Widget wdg = child; wdg != null; wdg = wdg.next) {
	    if(wdg == deco)
		continue;
	    if(!wdg.visible)
		continue;
	    Coord br = wdg.c.add(wdg.sz);
	    if(br.x > max.x)
		max.x = br.x;
	    if(br.y > max.y)
		max.y = br.y;
	}
	return(max);
    }

    public Area ca() {
	if(deco == null)
	    return(Area.sized(this.sz));
	else
	    return(deco.contarea());
    }

    public Coord csz() {
	return(ca().sz());
    }

    private void resize2(Coord sz) {
	Coord psz = this.sz;
	if(deco != null) {
	    deco.iresize(sz);
	    deco.c = deco.contarea().ul.inv();
	    this.sz = deco.sz;
	} else {
	    this.sz = sz;
	}
	for(Widget ch = child; ch != null; ch = ch.next)
	    ch.presize();
    }

    public void resize(Coord sz) {
	resize2(sz);
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "cap") {
	    String cap = (String)args[0];
	    chcap(cap.equals("") ? null : cap);
	} else if(msg == "dhide") {
	    chdeco(Utils.bv(args[0]) ? null : makedeco());
	} else {
	    super.uimsg(msg, args);
	}
    }

    public Coord xlate(Coord c, boolean in) {
	if(deco == null)
	    return(c);
	if(in)
	    return(c.add(deco.contarea().ul));
	else
	    return(c.sub(deco.contarea().ul));
    }

    public void drag(Coord off) {
	if(locked())
	    return;
	dm = ui.grabmouse(this);
	doff = off;
    }

    public boolean checkhit(Coord c) {
	return((deco == null) || deco.checkhit(c));
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(ev.propagate(this)) {
	    parent.setfocus(this);
	    raise();
	    return(true);
	}
	return(super.mousedown(ev));
    }

    public boolean mouseup(MouseUpEvent ev) {
	if(dm != null) {
	    dm.remove();
	    dm = null;
		preventDraggingOutside();
        if (this.cap != null)
            Utils.setprefc("wndc-" + this.cap, this.c);
        return(true);
	}
	return(super.mouseup(ev));
    }

    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	if(dm != null)
	    move(this.c.add(ev.c.sub(doff)));
    }

    public boolean handle(Event ev) {
	if(!ev.grabbed && (ev instanceof PointerEvent)) {
	    if(deco != null) {
		if(checkhit(((PointerEvent)ev).c)) {
		    super.handle(ev);
		    ev.propagate(this);
		    return(true);
		}
	    } else {
		super.handle(ev);
		return(ev.propagate(this));
	    }
	}
	return(super.handle(ev));
    }

    public boolean keydown(KeyDownEvent ev) {
	if(ev.propagate(this))
	    return(true);
	if(key_esc.match(ev) && !OptWnd.preventEscKeyFromClosingWindowsCheckBox.a) {
	    reqclose();
	    return(true);
	}
	return(super.keydown(ev));
    }

    private Runnable reqclose = () -> wdgmsg("close");
    public Window reqclose(Runnable reqclose) {
	this.reqclose = reqclose;
	return(this);
    }
    public void reqclose() {
	reqclose.run();
    }

    public static interface Animation {
	public boolean tick(double dt);
	public void draw(GOut g, Tex tex);
    }

    public static interface Transition<S extends Animation, H extends Animation> {
	public S show(Window wnd, H hiding);
	public H hide(Window wnd, S showing);
    }

    private Transition<?, ?> trans = null;
    private Animation anim = null;
    private String animst = null;
    public void tick(double dt) {
	super.tick(dt);
	barrelTakeTick();
	if(anim != null) {
	    if(anim.tick(dt)) {
		if(animst == "show") {
			preventDraggingOutside();
		} else if(animst == "hide") {
		    super.hide();
		} else if(animst == "dest") {
		    destroy();
		} else {
		    throw(new AssertionError(animst));
		}
		anim = null;
		animst = null;
	    }
	}
    }

    @SuppressWarnings("unchecked")
    private <H extends Animation> Animation show0(Transition<?, H> trans, Animation h) {
	return(trans.show(this, (H)h));
    }
    @SuppressWarnings("unchecked")
    private <S extends Animation> Animation hide0(Transition<S, ?> trans, Animation s) {
	return(trans.hide(this, (S)s));
    }

    public void settrans(Transition<?, ?> trans) {
	if(this.anim != null)
	    throw(new IllegalStateException(String.valueOf(this.anim)));
	this.trans = trans;
    }

    public boolean visible() {
	return(visible && ((animst == null) || (animst == "show")));
    }

    private void initanim() {
	if(trans == null)
	    trans = deftrans();
	if(visible) {
	    anim = trans.show(this, null);
	    animst = "show";
	}
    }

    public void show() {
	if(parent == null) {
	    super.show();
	    return;
	}
	if(!visible)
	    super.show();
	if(animst == null) {
	    anim = trans.show(this, null);
	    animst = "show";
	} else if(animst == "show") {
	} else if(animst == "hide") {
	    anim = show0(trans, anim);
	    animst = "show";
	} else if(animst == "dest") {
	} else {
	    throw(new AssertionError(animst));
	}
    }

    public void hide() {
	if(parent == null) {
	    super.hide();
	    return;
	}
	if(animst == null) {
	    anim = trans.hide(this, null);
	    animst = "hide";
	} else if(animst == "show") {
	    anim = hide0(trans, anim);
	    animst = "hide";
	} else if(animst == "hide") {
	} else if(animst == "dest") {
	} else {
	    throw(new AssertionError(animst));
	}
    }

    public void reqdestroy() {
	if(parent == null) {
	    super.reqdestroy();
	    return;
	}
	if(animst == null) {
	    anim = trans.hide(this, null);
	    animst = "dest";
	} else if(animst == "show") {
	    anim = hide0(trans, anim);
	    animst = "dest";
	} else if(animst == "hide") {
	    animst = "dest";
	} else if(animst == "dest") {
	} else {
	    throw(new AssertionError(animst));
	}
    }

    public static class NilAnim implements Animation {
	public boolean tick(double dt) {return(true);}
	public void draw(GOut g, Tex tex) {g.image(tex, Coord.z);}
    }

    public static final Transition<?, ?> niltrans = new Transition<Animation, Animation>() {
	    public NilAnim show(Window wnd, Animation hide) {return(new NilAnim());}
	    public NilAnim hide(Window wnd, Animation show) {return(new NilAnim());}
	};

    public abstract static class NormAnim implements Animation {
	public final double s;
	public final boolean rev;
	public double a = 0.0, na = 0.0;

	public NormAnim(double t, double fromn, boolean rev) {
	    this.s = 1.0 / t;
	    this.na = fromn;
	    this.rev = rev;
	    this.a = (rev ? (1.0 - fromn) : fromn) * t;
	}
	public NormAnim(double t, NormAnim from, boolean rev) {
	    this(t, (from == null) ? (rev ? 1.0 : 0.0) : from.na, rev);
	}
	public NormAnim(double t) {this(t, 0.0, false);}

	public boolean tick(double dt) {
	    a += dt;
	    double na = Math.min(a * s, 1.0);
	    stick(this.na = rev ? (1.0 - na) : na);
	    return(na >= 1.0);
	}

	public void stick(double a) {}
    }

    public static class FadeAnim extends NormAnim {
	public static final double minfac = 0.1;
	public static final double time = 0.1;

	public FadeAnim(boolean hide, FadeAnim from) {
	    super(time, from, hide);
	}

	public void draw(GOut g, Tex tex) {
	    double na = Utils.smoothstep(this.na);
	    g.chcolor(255, 255, 255, (int)(na * 255));
	    Coord sz = tex.sz();
	    double fac = minfac * (1.0 - na);
	    g.image(tex, Coord.of((int)(sz.x * fac), (int)(sz.y * fac)),
		    Coord.of((int)(sz.x * (1.0 - (fac * 2))), (int)(sz.y * (1.0 - (fac * 2)))));
	}

	public static final Transition<?, ?> trans = new Transition<FadeAnim, FadeAnim>() {
		public FadeAnim show(Window wnd, FadeAnim hide) {return(new FadeAnim(false, hide));}
		public FadeAnim hide(Window wnd, FadeAnim show) {return(new FadeAnim(true,  show));}
	    };
    }

    protected Transition<?, ?> deftrans() {
	return(FadeAnim.trans);
    }

    public static void main(String[] args) throws InterruptedException {
	Window wnd = new Window(new Coord(300, 200), "Inventory", true);
	boolean[] done = {false};
	new haven.rs.DrawBuffer(haven.iosys.tk.Acephal.instance().env(), new Coord(512, 512))
	    .draw(g -> {
		    wnd.draw(g);
		    g.getimage(img -> {
			Debug.dumpimage(img, args[0]);
			synchronized(done) {
			    done[0] = true;
			    done.notifyAll();
			}
		    });
	    });
	synchronized(done) {
	    while(!done[0])
		done.wait();
	}
    }

    public static final Coord guiTopLeftCornerDiff = UI.scale(15, 8); // ND: You'd think gui.c starts in the top left corner of the screen. You'd be wrong. I don't even know how.
    public static final Coord windowBottomRightCornerDiff = UI.scale(10, 19); // ND: I guess the window size Coord says it's larger than what it actually is, so I gotta correct this too.
    public static final Coord dragResizeDiff = UI.scale(51, 72); // ND: I don't even know how to explain this one. Just trust the code.

	public void preventDraggingOutside() { // ND: This code is just straight up spaghetti, I know.
        if (ui == null || ui.gui == null) {
            return;
        }
        Coord guiSize = ui.gui.sz;
        Coord windowSize = this.sz;
        if (windowSize.x > 800 || windowSize.y > 500 || !OptWnd.snapWindowsBackInsideCheckBox.a) {
            if (this.c.x < - guiTopLeftCornerDiff.x - (int)(windowSize.x * 0.70)) this.c.x = - guiTopLeftCornerDiff.x - (int)(windowSize.x * 0.70);
            if (this.c.y < - guiTopLeftCornerDiff.y - (int)(windowSize.y * 0.70)) this.c.y = - guiTopLeftCornerDiff.y - (int)(windowSize.y * 0.70);
            if (this.c.x > (guiSize.x - (int)(windowSize.x * 0.30) + windowBottomRightCornerDiff.x)) this.c.x = guiSize.x - (int)(windowSize.x * 0.30) + windowBottomRightCornerDiff.x;
            if (this.c.y > (guiSize.y - (int)(windowSize.y * 0.30) + windowBottomRightCornerDiff.y)) this.c.y = guiSize.y - (int)(windowSize.y * 0.30) + windowBottomRightCornerDiff.y;
        } else {
            if (this.c.x < - guiTopLeftCornerDiff.x) this.c.x = - guiTopLeftCornerDiff.x;
            if (this.c.y < - guiTopLeftCornerDiff.y) this.c.y = - guiTopLeftCornerDiff.y;
            if (this.c.x > (guiSize.x - windowSize.x + windowBottomRightCornerDiff.x)) this.c.x = guiSize.x - windowSize.x + windowBottomRightCornerDiff.x;
            if (this.c.y > (guiSize.y - windowSize.y + windowBottomRightCornerDiff.y)) this.c.y = guiSize.y - windowSize.y + windowBottomRightCornerDiff.y;
        }
	}

    public void preventResizingOutside() {
        if (ui == null || ui.gui == null) {
            return;
        }
        Coord guiSize = ui.gui.sz;
        Coord windowSize = this.sz;
        // ND: This prevents us from resizing it larger than the game window size
        if(windowSize.x > guiSize.x - (dragResizeDiff.x - guiTopLeftCornerDiff.x - windowBottomRightCornerDiff.x)) {this.resize(guiSize.x - (dragResizeDiff.x - guiTopLeftCornerDiff.x - windowBottomRightCornerDiff.x), windowSize.y - dragResizeDiff.y); windowSize = this.sz;}
        if(windowSize.y > guiSize.y - (dragResizeDiff.y - guiTopLeftCornerDiff.y - windowBottomRightCornerDiff.y)) {this.resize(windowSize.x - dragResizeDiff.x, guiSize.y - (dragResizeDiff.y - guiTopLeftCornerDiff.y - windowBottomRightCornerDiff.y)); windowSize = this.sz;}
    }

    @Override
    public void dispose() {
        super.dispose();
        if (this.cap != null)
            Utils.setprefc("wndc-" + this.cap, this.c);
    }

    /* H4D: barrel "Take #" row (doc/H4D-features/barrel-take-amount.md). Added once the server has put
     * the barrel's own Take button in, and kept under the rest of the window's contents. */
    private BarrelTakeAmount barrelTake = null;
    private boolean barrelTakeChecked = false;
    private void barrelTakeTick() {
	if(!BarrelTakeAmount.WINDOW_CAP.equals(cap))
	    return;
	if(barrelTake == null) {
	    if(barrelTakeChecked)
		return;
	    if((OptWnd.barrelTakeAmountCheckBox != null) && !OptWnd.barrelTakeAmountCheckBox.a) {
		barrelTakeChecked = true;
		return;
	    }
	    if(BarrelTakeAmount.findTake(this) == null)
		return;
	    barrelTakeChecked = true;
	    barrelTake = add(new BarrelTakeAmount(), Coord.z);
	}
	int y = 0;
	for(Widget ch = child; ch != null; ch = ch.next) {
	    if((ch == barrelTake) || (ch == deco) || !ch.visible())
		continue;
	    y = Math.max(y, ch.c.y + ch.sz.y);
	}
	Coord want = Coord.of(0, y + UI.scale(5));
	if(!want.equals(barrelTake.c)) {
	    barrelTake.move(want);
	    pack();
	}
    }

    @Override
    public <T extends Widget> T add(T child) {
        super.add(child);
        enhanceWidgets(child);
        return(child);
    }

	private boolean shouldAttachLocalExtInventory(Inventory inv) {
		if (inv == null || getchild(ExtInventory.class) != null || cap == null)
			return false;
		if (Inventory.PLAYER_INVENTORY_NAMES.contains(cap))
			return false;
		return shouldHaveInventoryButtons();
	}

	private boolean attachLocalExtInventory(Inventory inv) {
		if (!shouldAttachLocalExtInventory(inv))
			return false;

		Coord invc = new Coord(inv.c);
		inv.unlink();
		childseq++;

		add(new ExtInventory(inv, ExtInventory.TransferMode.LOCAL), invc);
		pack();
		return true;
	}

    private <T extends Widget> void enhanceWidgets(T child) {
        try {
            if (child instanceof Inventory && attachLocalExtInventory((Inventory) child))
                return;

            if (child instanceof Inventory || child instanceof ExtInventory) {
                if (deco instanceof DefaultDeco) {
					try {
						((DefaultDeco) deco).addStackBtn();
					} catch (Exception e) {
						e.printStackTrace();
					}
					try {
						((DefaultDeco) deco).addUnstackBtn();
					} catch (Exception e) {
						e.printStackTrace();
					}
                    try {
                        ((DefaultDeco) deco).addSortBtn();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
					if (child instanceof ExtInventory) {
						try {
							((DefaultDeco) deco).addExtListBtn();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					((DefaultDeco) deco).refreshInventoryButtons();

				}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
