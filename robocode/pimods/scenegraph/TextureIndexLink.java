/******************************************************************************
 * Copyright (c) 2008 Marco Della Vedova, Matteo Foppiano
 * and Pimods contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.pixelinstrument.net/license/cpl-v10.html
 ******************************************************************************/

package pimods.scenegraph;


import com.sun.opengl.util.texture.Texture;

/**
 * @author Marco Della Vedova - pixelinstrument.net
 * @author Matteo Foppiano - pixelinstrument.net
 *
 */

public class TextureIndexLink {
	private Texture textureIndexLink[][];

	public TextureIndexLink() {
	}

	public TextureIndexLink( Texture[][] textureIndexLink ) {
		this.textureIndexLink = textureIndexLink;
	}

	public void setTextureIndexLink( Texture[][] textureIndexLink ) {
		this.textureIndexLink = textureIndexLink;
	}

	public Texture[][] getTextureIndexLink() {
		return( this.textureIndexLink );
	}
}