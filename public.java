public Map<String, Map<Double, Double>> calcRefAlpha(MSDate lastDate,
                                                     List<LocalDate> fixedExpiries) {

    String referenceBasket   = domain.getProperty("BasketVolSurfaceCalculator.referenceBasket");
    String basketPriceSource = domain.getProperty("BasketVolSurfaceCalculator.componentPriceSource");
    // Interpret as a floor on THETA (recommend 0.0 in config)
    double minTheta          = domain.getProperty("BasketVolSurfaceCalculator.minRefAlpha", 0.0);

    TreeMap<String, Double> underlyingAndWeight =
        getBasketComponentsWeights(referenceBasket, basketPriceSource);

    if (underlyingAndWeight == null) {
        logger.error("Cannot get constituents for reference basket: {}", referenceBasket);
        return Collections.emptyMap();
    }

    TreeMap<String, Double> refSingletonBasket = new TreeMap<String, Double>() {{
        put(referenceBasket, 1.0);
    }};

    Map<String, Map<Double, Map<String, Double>>> volEODData =
        fetchVolData(executor, new ArrayList<>(underlyingAndWeight.keySet()),
                     false, lastDate, fixedExpiries);

    Map<String, Map<Double, Map<String, Double>>> refVolEODData =
        fetchVolData(executor, Collections.singletonList(referenceBasket),
                     false, lastDate, fixedExpiries);

    Map<String, Map<String, Double>> realizedVolData =
        fetchRealizedVolData(executor, underlyingAndWeight, lastDate);

    Map<String, Map<String, Double>> refRealizedVolData =
        fetchRealizedVolData(executor, refSingletonBasket, lastDate);

    Set<String> commonTickers = getCommonTickers(volEODData, realizedVolData, underlyingAndWeight);
    TreeMap<String, Double> normalizedWeights = getNormalizedWeight(underlyingAndWeight, commonTickers);

    Map<String, Map<Double, Double>> impliedCorrelationMatrix =
        calcCorrelationMatrix(volEODData, refVolEODData, normalizedWeights, referenceBasket);

    Map<String, Double> realizedCorrelationMatrix =
        realizedCorrelationCalculator.calculateRealizedCorrelationMatrix(
            realizedVolData, refRealizedVolData, normalizedWeights, referenceBasket);

    String realizedTenorKey = domain.getProperty("basketVolSurfaceCalculator.realizedVolTenor");
    double realizedCorr     = realizedCorrelationMatrix.getOrDefault(realizedTenorKey, Double.NaN);
    logger.info("Realized correlation for reference basket {} is {}", referenceBasket, realizedCorr);

    // --- NEW: θ = atanh(rho_imp) - atanh(rho_real)
    final double EPS  = 1e-6;
    final double RMIN = -1.0 + EPS;
    final double RMAX =  1.0 - EPS;

    java.util.function.DoubleUnaryOperator clip =
        r -> Math.max(RMIN, Math.min(RMAX, r));
    java.util.function.DoubleUnaryOperator atanh =
        r -> 0.5d * Math.log((1.0 + r) / (1.0 - r));

    double zReal = atanh.applyAsDouble(clip.applyAsDouble(realizedCorr));

    Map<String, Map<Double, Double>> thetaByTenorStrike = new HashMap<>();

    for (Map.Entry<String, Map<Double, Double>> tenorEntry : impliedCorrelationMatrix.entrySet()) {
        String tenor = tenorEntry.getKey();
        Map<Double, Double> strikeCorrs = tenorEntry.getValue();

        thetaByTenorStrike.putIfAbsent(tenor, new HashMap<>());
        for (Map.Entry<Double, Double> strikeCorrEntry : strikeCorrs.entrySet()) {
            double strike      = strikeCorrEntry.getKey();
            double rhoImp      = strikeCorrEntry.getValue();

            double zImp  = atanh.applyAsDouble(clip.applyAsDouble(rhoImp));
            double theta = zImp - zReal;                    // <-- the “new alpha”

            if (!Double.isFinite(theta)) {
                logger.warn("Non-finite theta for tenor {}, strike {} (rhoImp={}, rhoReal={}); using floor {}",
                            tenor, strike, rhoImp, realizedCorr, minTheta);
                theta = minTheta;
            } else {
                theta = Math.max(theta, minTheta);          // enforce non-negativity if desired
            }

            thetaByTenorStrike.get(tenor).put(strike, theta);
        }
    }

    return thetaByTenorStrike;   // same type as before; values now mean θ (z-shift)
}
