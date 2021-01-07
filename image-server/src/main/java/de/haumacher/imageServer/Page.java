/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.util.xml.RenderContext;
import de.haumacher.util.xml.XmlAppendable;
import de.haumacher.util.xml.XmlFragment;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Page implements XmlFragment {
	
	private CharSequence _title;
	private XmlFragment _content;

	static final String FA_VERSION;
	
	static {
		FA_VERSION = MavenUtil.artifactVersion("org.webjars", "font-awesome");
	}

	/** 
	 * Creates a {@link Page}.
	 */
	public Page(CharSequence title, XmlFragment content) {
		_title = title;
		_content = content;
	}

	@Override
	public void write(RenderContext context, XmlAppendable out) throws IOException {
		out.begin(HTML);
		{
			out.begin(HEAD);
			{
				out.begin(TITLE); out.append(_title); out.end();
				
				out.begin(META);
				out.attr(NAME_ATTR, "viewport");
				out.attr(CONTENT_ATTR, "width=device-width, initial-scale=1.0");
				out.endEmpty();
				
				out.begin(LINK);
				out.attr(REL_ATTR, "stylesheet");
				out.attr(TYPE_ATTR, "text/css");
				out.openAttr(HREF_ATTR);
				{
					out.append(context.getContextPath());
					out.append(Main.STATIC_PREFIX + "/style/valbum.css");
				}
				out.closeAttr();
				out.endEmpty();
				
				out.begin(LINK);
				out.attr(REL_ATTR, "stylesheet");
				out.attr(TYPE_ATTR, "text/css");
				out.openAttr(HREF_ATTR);
				{
					out.append(context.getContextPath());
					out.append(Main.STATIC_PREFIX + "/webjars/font-awesome/");
					out.append(FA_VERSION);
					out.append("/css/all.css");
				}
				out.closeAttr();
				out.endEmpty();
				
				out.begin(SCRIPT);
				out.attr(TYPE_ATTR, "text/javascript");
				out.openAttr(SRC_ATTR);
				{
					out.append(context.getContextPath());
					out.append(Main.STATIC_PREFIX);
					out.append("/script/valbum.js");
				}
				out.closeAttr();
				out.end();
			}
			out.end();
			
			out.begin(BODY);
			{
				_content.write(context, out);
			}
			out.end();
		}
		out.end();
	}
	
}
