package spacesettlers.clients;

import java.awt.Color;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;

import spacesettlers.actions.AbstractAction;
import spacesettlers.actions.DoNothingAction;
import spacesettlers.actions.MoveToObjectAction;
import spacesettlers.actions.PurchaseCosts;
import spacesettlers.actions.PurchaseTypes;
import spacesettlers.graphics.SpacewarGraphics;
import spacesettlers.graphics.StarGraphics;
import spacesettlers.objects.AbstractActionableObject;
import spacesettlers.objects.AbstractObject;
import spacesettlers.objects.Asteroid;
import spacesettlers.objects.Base;
import spacesettlers.objects.Beacon;
import spacesettlers.objects.Flag;
import spacesettlers.objects.Ship;
import spacesettlers.objects.powerups.SpaceSettlersPowerupEnum;
import spacesettlers.objects.resources.ResourcePile;
import spacesettlers.simulator.Toroidal2DPhysics;
import spacesettlers.utilities.Position;

/**
 * A pacifist flag collector client that handles multiple agents in the team.  The heuristic works as follows:
 * 
 *   The nearest and healthy ship is assigned to go get the flag and bring it back.
 *   The other ships are assigned to resource collection.
 *   Resources are used to buy additional ships and bases (with the idea that bases are better to have near the 
 *   enemy flag locations).
 * 
 * (similar to PassiveHeuritsicAsteroidCollector).  
 *  
 * @author amy
 */
public class PacifistFlagCollectorTeamClient extends TeamClient {
	HashMap <UUID, Ship> asteroidToShipMap;
	HashMap <UUID, Boolean> aimingForBase;
	
	/**
	 * Assigns ships to asteroids and beacons, as described above
	 */
	public Map<UUID, AbstractAction> getMovementStart(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		HashMap<UUID, AbstractAction> actions = new HashMap<UUID, AbstractAction>();
		Ship flagShip;

		// get the flag carrier, if we have one
		flagShip = getFlagCarrier(space, actionableObjects);
		
		// we don't have a ship carrying a flag, so find the best choice (if it exists)
		if (flagShip == null) {
			flagShip = findHealthiestShipNearFlag(space, actionableObjects);
		}

		// loop through each ship and assign it to either get energy (if needed for health) or
		// resources (as long as it isn't the flagShip)
		for (AbstractObject actionable :  actionableObjects) {
			if (actionable instanceof Ship) {
				Ship ship = (Ship) actionable;

				AbstractAction action;

				if (flagShip != null && ship.equals(flagShip)) {
					if (flagShip.isCarryingFlag()) {
						//System.out.println("We have a flag carrier!");
						Base base = findNearestBase(space, ship);
						//System.out.println("Flag ship before computing action: " + flagShip);
						action = new MoveToObjectAction(space, ship.getPosition(), base);
						//System.out.println("Aiming for base with action " + action);
						aimingForBase.put(ship.getId(), true);
						//System.out.println("Flag ship after computing action: " + flagShip);
					} else {
						Flag enemyFlag = getEnemyFlag(space);
						action = new MoveToObjectAction(space, ship.getPosition(), enemyFlag,
								enemyFlag.getPosition().getTranslationalVelocity());
					}
				} else {
					action = getAsteroidCollectorAction(space, ship);
				}

				// save the action for this ship
				actions.put(ship.getId(), action);
			} else {
				// bases do nothing
				actions.put(actionable.getId(), new DoNothingAction());
			}
		} 
		return actions;
	}

	/**
	 * Get the flag carrier (if there is one).  Return null if there isn't a current flag carrier
	 * 
	 * @param space
	 * @param actionableObjects
	 * @return
	 */
	private Ship getFlagCarrier(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		for (AbstractObject actionable :  actionableObjects) {
			if (actionable instanceof Ship) {
				Ship ship = (Ship) actionable;
				
				if (ship.isCarryingFlag()) {
					return ship;
				}
			}
		}
		return null;
	}
	
	/**
	 * Finds and returns the enemy flag
	 * @param space
	 * @return
	 */
	private Flag getEnemyFlag(Toroidal2DPhysics space) {
		Flag enemyFlag = null;
		for (Flag flag : space.getFlags()) {
			if (flag.getTeamName().equalsIgnoreCase(getTeamName())) {
				continue;
			} else {
				enemyFlag = flag;
			}
		}
		return enemyFlag;
	}
	
	
	/**
	 * Finds the ship with the highest health and nearest the flag
	 * 
	 * @param space
	 * @param actionableObjects
	 * @return
	 */
	private Ship findHealthiestShipNearFlag(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		double minDistance = Double.MAX_VALUE;
		double maxHealth = Double.MIN_VALUE;
		int minHealth = 2000;
		Ship bestShip = null;

		// first find the enemy flag
		Flag enemyFlag = getEnemyFlag(space);

		// now find the healthiest ship that has at least the required minimum energy 
		// if no ships meet that criteria, return null
		for (AbstractObject actionable :  actionableObjects) {
			if (actionable instanceof Ship) {
				Ship ship = (Ship) actionable;
				
				double dist = space.findShortestDistance(ship.getPosition(), enemyFlag.getPosition());
				if (dist < minDistance && ship.getEnergy() > minHealth) {
					if (ship.getEnergy() > maxHealth) {
						minDistance = dist;
						maxHealth = ship.getEnergy();
						bestShip = ship;
					}
				}
			}
		}
		
		return bestShip;
		
	}
	
	
	/**
	 * Gets the action for the asteroid collecting ship
	 * @param space
	 * @param ship
	 * @return
	 */
	private AbstractAction getAsteroidCollectorAction(Toroidal2DPhysics space,
			Ship ship) {
		AbstractAction current = ship.getCurrentAction();
		Position currentPosition = ship.getPosition();

		// aim for a beacon if there isn't enough energy
		if (ship.getEnergy() < 2000) {
			Beacon beacon = pickNearestBeacon(space, ship);
			AbstractAction newAction = null;
			// if there is no beacon, then just skip a turn
			if (beacon == null) {
				newAction = new DoNothingAction();
			} else {
				newAction = new MoveToObjectAction(space, currentPosition, beacon);
			}
			aimingForBase.put(ship.getId(), false);
			return newAction;
		}

		// if the ship has enough resourcesAvailable, take it back to base
		if (ship.getResources().getTotal() > 500) {
			Base base = findNearestBase(space, ship);
			AbstractAction newAction = new MoveToObjectAction(space, currentPosition, base);
			aimingForBase.put(ship.getId(), true);
			return newAction;
		}

		// did we bounce off the base?
		if (ship.getResources().getTotal() == 0 && ship.getEnergy() > 2000 && aimingForBase.containsKey(ship.getId()) && aimingForBase.get(ship.getId())) {
			current = null;
			aimingForBase.put(ship.getId(), false);
		}

		// otherwise aim for the asteroid
		if (current == null || current.isMovementFinished(space)) {
			aimingForBase.put(ship.getId(), false);
			Asteroid asteroid = pickHighestValueNearestFreeAsteroid(space, ship);

			AbstractAction newAction = null;

			if (asteroid != null) {
				asteroidToShipMap.put(asteroid.getId(), ship);
				newAction = new MoveToObjectAction(space, currentPosition, asteroid, 
						asteroid.getPosition().getTranslationalVelocity());
			}
			
			return newAction;
		} 
		
		return ship.getCurrentAction();
	}


	/**
	 * Find the base for this team nearest to this ship
	 * 
	 * @param space
	 * @param ship
	 * @return
	 */
	private Base findNearestBase(Toroidal2DPhysics space, Ship ship) {
		double minDistance = Double.MAX_VALUE;
		Base nearestBase = null;

		for (Base base : space.getBases()) {
			if (base.getTeamName().equalsIgnoreCase(ship.getTeamName())) {
				double dist = space.findShortestDistance(ship.getPosition(), base.getPosition());
				if (dist < minDistance) {
					minDistance = dist;
					nearestBase = base;
				}
			}
		}
		return nearestBase;
	}

	/**
	 * Returns the asteroid of highest value that isn't already being chased by this team
	 * 
	 * @return
	 */
	private Asteroid pickHighestValueNearestFreeAsteroid(Toroidal2DPhysics space, Ship ship) {
		Set<Asteroid> asteroids = space.getAsteroids();
		int bestMoney = Integer.MIN_VALUE;
		Asteroid bestAsteroid = null;
		double minDistance = Double.MAX_VALUE;

		for (Asteroid asteroid : asteroids) {
			if (!asteroidToShipMap.containsKey(asteroid.getId())) {
				if (asteroid.isMineable() && asteroid.getResources().getTotal() > bestMoney) {
					double dist = space.findShortestDistance(asteroid.getPosition(), ship.getPosition());
					if (dist < minDistance) {
						bestMoney = asteroid.getResources().getTotal();
						//System.out.println("Considering asteroid " + asteroid.getId() + " as a best one");
						bestAsteroid = asteroid;
						minDistance = dist;
					}
				}
			}
		}
		//System.out.println("Best asteroid has " + bestMoney);
		return bestAsteroid;
	}


	/**
	 * Find the nearest beacon to this ship
	 * @param space
	 * @param ship
	 * @return
	 */
	private Beacon pickNearestBeacon(Toroidal2DPhysics space, Ship ship) {
		// get the current beacons
		Set<Beacon> beacons = space.getBeacons();

		Beacon closestBeacon = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (Beacon beacon : beacons) {
			double dist = space.findShortestDistance(ship.getPosition(), beacon.getPosition());
			if (dist < bestDistance) {
				bestDistance = dist;
				closestBeacon = beacon;
			}
		}

		return closestBeacon;
	}



	@Override
	public void getMovementEnd(Toroidal2DPhysics space, Set<AbstractActionableObject> actionableObjects) {
		ArrayList<Asteroid> finishedAsteroids = new ArrayList<Asteroid>();

		for (UUID asteroidId : asteroidToShipMap.keySet()) {
			Asteroid asteroid = (Asteroid) space.getObjectById(asteroidId);
			if (asteroid == null || !asteroid.isAlive() || asteroid.isMoveable()) {
 				finishedAsteroids.add(asteroid);
				//System.out.println("Removing asteroid from map");
			}
		}

		for (Asteroid asteroid : finishedAsteroids) {
			asteroidToShipMap.remove(asteroid.getId());
		}


	}

	/**
	 * Demonstrates one way to read in knowledge from a file
	 */
	@Override
	public void initialize(Toroidal2DPhysics space) {
		asteroidToShipMap = new HashMap<UUID, Ship>();
		aimingForBase = new HashMap<UUID, Boolean>();
		
	}

	/**
	 * Demonstrates saving out to the xstream file
	 * You can save out other ways too.  This is a human-readable way to examine
	 * the knowledge you have learned.
	 */
	@Override
	public void shutDown(Toroidal2DPhysics space) {
	}

	@Override
	public Set<SpacewarGraphics> getGraphics() {
		return null;
	}

	@Override
	/**
	 * If there is enough resourcesAvailable, buy a base.  Place it by finding a ship that is sufficiently
	 * far away from the existing bases
	 */
	public Map<UUID, PurchaseTypes> getTeamPurchases(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects, 
			ResourcePile resourcesAvailable, 
			PurchaseCosts purchaseCosts) {

		HashMap<UUID, PurchaseTypes> purchases = new HashMap<UUID, PurchaseTypes>();
		double BASE_BUYING_DISTANCE = 200;
		boolean bought_base = false;
		int numBases, numShips;

		// count the number of ships for the base/ship buying algorithm
		numShips = 0;
		for (AbstractActionableObject actionableObject : actionableObjects) {
			if (actionableObject instanceof Ship) {
				numShips++;
			}
		}
		
		// now see if we can afford a base or a ship.  We want a base but we also really want a 3rd ship
		// try to balance
		if (purchaseCosts.canAfford(PurchaseTypes.BASE, resourcesAvailable)) {
			for (AbstractActionableObject actionableObject : actionableObjects) {
				if (actionableObject instanceof Ship) {
					Ship ship = (Ship) actionableObject;
					Set<Base> bases = space.getBases();

					// how far away is this ship to a base of my team?
					boolean buyBase = true;
					numBases = 0;
					for (Base base : bases) {
						if (base.getTeamName().equalsIgnoreCase(getTeamName())) {
							numBases++;
							double distance = space.findShortestDistance(ship.getPosition(), base.getPosition());
							if (distance < BASE_BUYING_DISTANCE) {
								buyBase = false;
							}
						}
					}
					if (buyBase && numBases < numShips) {
						purchases.put(ship.getId(), PurchaseTypes.BASE);
						bought_base = true;
						System.out.println("Pacifist Flag Collector is buying a base!");
						break;
					}
				}
			}		
		} 
		
		// can I buy a ship?
		if (purchaseCosts.canAfford(PurchaseTypes.SHIP, resourcesAvailable) && bought_base == false) {
			for (AbstractActionableObject actionableObject : actionableObjects) {
				if (actionableObject instanceof Base) {
					Base base = (Base) actionableObject;
					
					purchases.put(base.getId(), PurchaseTypes.SHIP);
					System.out.println("Pacifist Flag Collector is buying a ship!");
					break;
				}

			}

		}


		return purchases;
	}

	/**
	 * The pacifist flag collector doesn't use power ups 
	 * @param space
	 * @param actionableObjects
	 * @return
	 */
	@Override
	public Map<UUID, SpaceSettlersPowerupEnum> getPowerups(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		HashMap<UUID, SpaceSettlersPowerupEnum> powerUps = new HashMap<UUID, SpaceSettlersPowerupEnum>();

		
		return powerUps;
	}

}
