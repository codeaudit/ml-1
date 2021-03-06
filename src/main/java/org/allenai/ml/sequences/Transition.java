package org.allenai.ml.sequences;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Value;


/**
 * A transition represents `(from, to)` transition between states. We store the `selfIndex` of the from and to state
 * as well as its index relative to other transitions. This is meant to only be used internal to this package.
 *
 * @See StateSpace
 */
@Value
public class Transition {
    public final int fromState;
    public final int toState;
    public final int selfIndex;
}
