package com.clele.parts.model;

/**
 * Kind of label stock loaded in a printer, as detected over IPP. Die-cut labels have a fixed
 * length and must be declared as such in the print job; continuous tape does not.
 */
public enum MediaKind {
    CONTINUOUS,
    DIE_CUT
}
