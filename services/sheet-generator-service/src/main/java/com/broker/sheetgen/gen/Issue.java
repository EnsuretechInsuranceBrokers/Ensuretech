package com.broker.sheetgen.gen;

/** A problem found in one data row (1-based; 0 = the sheet as a whole). */
public record Issue(int row, String code, String message) {}
