/* Include.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Wed Sep 28 18:01:03     2005, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.zul.html;

import java.io.Writer;
import java.io.IOException;

import com.potix.lang.Objects;

import com.potix.zk.ui.UiException;
import com.potix.zk.ui.WrongValueException;
import com.potix.zk.ui.sys.UiEngine;
import com.potix.zk.ui.sys.WebAppCtrl;

import com.potix.zul.html.impl.XulElement;

/**
 * Includes the result generated by any servlet.
 *
 * <p>Non-XUL extension.
 *
 * <p>If the servlet is eventually another ZUL page, the page will be
 * added to the current desktop when this element is added to
 * the current desktop.
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public class Include extends XulElement {
	protected String _src;
	private boolean _localized;

	public Include() {
	}
	public Include(String src) {
		setSrc(src);
	}

	/** Returns the src.
	 * <p>Default: null.
	 */
	public String getSrc() {
		return _src;
	}
	/** Sets the src.
	 * <p>If src is changed, the whole component is invalidate.
	 * Thus, you want to smart-update, you have to override this method.
	 *
	 * @param src the source URL. If null or empty, nothing is included.
	 */
	public void setSrc(String src) throws WrongValueException {
		if (src != null && src.length() == 0) src = null;

		if (!Objects.equals(_src, src)) {
			_src = src;
			invalidate(INNER);
		}
	}

	/** Returns whether the source depends on the current Locale.
	 * If true, it will search xxx_en_US.yyy, xxx_en.yyy and xxx.yyy
	 * for the proper content, where src is assumed to be xxx.yyy.
	 *
	 * <p>Default: false;
	 */
	public final boolean getLocalized() {
		return _localized;
	}
	/** Sets whether the source depends on the current Locale.
	 */
	public final void setLocalized(boolean localized) {
		if (_localized != localized) {
			_localized = localized;
			invalidate(INNER);
		}
	}

	//-- Component --//
	/** Default: not childable.
	 */
	public boolean isChildable() {
		return false;
	}
	public void redraw(Writer out) throws IOException {
		final UiEngine ueng =
			((WebAppCtrl)getDesktop().getWebApp()).getUiEngine();
		ueng.pushOwner(this);
		try {
			super.redraw(out);
		} finally {
			ueng.popOwner();
		}
	}
}
