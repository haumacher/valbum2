package de.haumacher.imageServer.shared.model;

/**
 * The share links on a folder and its ancestors, answered by
 * <code>&lt;folder&gt;/?type=shares</code>.
 *
 * <p>
 * Only the owner of the space and the admin may ask, and no answer ever carries a token.
 * </p>
 */
public class ShareLinkList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ShareLinkList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ShareLinkList create() {
		return new de.haumacher.imageServer.shared.model.ShareLinkList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ShareLinkList} type in JSON format. */
	public static final String SHARE_LINK_LIST__TYPE = "ShareLinkList";

	/** @see #getLinks() */
	private static final String LINKS__PROP = "links";

	private final java.util.List<de.haumacher.imageServer.shared.model.ShareLink> _links = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ShareLinkList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ShareLinkList#create()
	 */
	protected ShareLinkList() {
		super();
	}

	/**
	 * The links covering the addressed folder, the nearest one first.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ShareLink> getLinks() {
		return _links;
	}

	/**
	 * @see #getLinks()
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkList setLinks(java.util.List<? extends de.haumacher.imageServer.shared.model.ShareLink> value) {
		internalSetLinks(value);
		return this;
	}

	/** Internal setter for {@link #getLinks()} without chain call utility. */
	protected final void internalSetLinks(java.util.List<? extends de.haumacher.imageServer.shared.model.ShareLink> value) {
		if (value == null) throw new IllegalArgumentException("Property 'links' cannot be null.");
		_links.clear();
		_links.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getLinks()} list.
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkList addLink(de.haumacher.imageServer.shared.model.ShareLink value) {
		internalAddLink(value);
		return this;
	}

	/** Implementation of {@link #addLink(de.haumacher.imageServer.shared.model.ShareLink)} without chain call utility. */
	protected final void internalAddLink(de.haumacher.imageServer.shared.model.ShareLink value) {
		_links.add(value);
	}

	/**
	 * Removes a value from the {@link #getLinks()} list.
	 */
	public final void removeLink(de.haumacher.imageServer.shared.model.ShareLink value) {
		_links.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ShareLinkList readShareLinkList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ShareLinkList result = new de.haumacher.imageServer.shared.model.ShareLinkList();
		result.readContent(in);
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(LINKS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ShareLink x : getLinks()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LINKS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addLink(de.haumacher.imageServer.shared.model.ShareLink.readShareLink(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
