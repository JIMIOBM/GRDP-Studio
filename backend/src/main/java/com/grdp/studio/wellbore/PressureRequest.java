package com.grdp.studio.wellbore;

import java.util.List;

public class PressureRequest extends TemperatureRequest {
    public List<String> models = List.of("HB", "MB");
    public List<Double> profileDepth;
    public List<Double> profileTemperature;
}
