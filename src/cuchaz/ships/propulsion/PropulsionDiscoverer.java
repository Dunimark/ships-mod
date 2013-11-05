/*******************************************************************************
 * Copyright (c) 2013 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.ships.propulsion;

import java.util.List;

import cuchaz.modsShared.BlockSide;
import cuchaz.ships.ShipWorld;

public interface PropulsionDiscoverer
{
	public List<PropulsionMethod> getPropulsionMethods( ShipWorld world, BlockSide frontDirection );
}
