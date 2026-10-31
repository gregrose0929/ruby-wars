package dev.rubymod.game.convoy;

/** For autotests: where the convoy's set pieces are along the road. */
public final class ConvoyRouteView {
	private ConvoyRouteView() {
	}

	public static int logs() {
		return ConvoyEvents.LOGS;
	}

	public static int start(int stage) {
		return ConvoyRoute.START[stage];
	}

	public static int square() {
		return ConvoyRoute.START[1] + ConvoyEvents.SQUARE;
	}

	public static int gap() {
		return ConvoyRoute.GAP_FROM;
	}

	public static int snow() {
		return ConvoyEvents.SNOW_WALL;
	}

	public static int courtyard() {
		return ConvoyRoute.COURTYARD;
	}
}
