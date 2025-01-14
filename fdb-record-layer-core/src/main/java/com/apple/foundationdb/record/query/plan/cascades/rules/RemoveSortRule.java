/*
 * RemoveSortRule.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.cascades.rules;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.query.plan.cascades.ExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.GroupExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.KeyPart;
import com.apple.foundationdb.record.query.plan.cascades.Ordering;
import com.apple.foundationdb.record.query.plan.cascades.PlanPartition;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRule;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalSortExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.ReferenceMatchers;
import com.apple.foundationdb.record.query.plan.cascades.properties.OrderingProperty;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryCoveringIndexPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryIndexPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.google.common.collect.ImmutableList;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.AnyMatcher.any;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ListMatcher.exactly;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.QuantifierMatchers.forEachQuantifierOverRef;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ReferenceMatchers.planPartitions;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RelationalExpressionMatchers.logicalSortExpression;

/**
 * A rule that implements a sort expression by removing this expression if appropriate.
 */
@API(API.Status.EXPERIMENTAL)
@SuppressWarnings("PMD.TooManyStaticImports")
public class RemoveSortRule extends PlannerRule<LogicalSortExpression> {
    @Nonnull
    private static final BindingMatcher<PlanPartition> innerPlanPartitionMatcher = ReferenceMatchers.anyPlanPartition();

    @Nonnull
    private static final BindingMatcher<ExpressionRef<? extends RelationalExpression>> innerReferenceMatcher =
            planPartitions(any(innerPlanPartitionMatcher));
    @Nonnull
    private static final BindingMatcher<Quantifier.ForEach> innerQuantifierMatcher = forEachQuantifierOverRef(innerReferenceMatcher);
    @Nonnull
    private static final BindingMatcher<LogicalSortExpression> root = logicalSortExpression(exactly(innerQuantifierMatcher));

    public RemoveSortRule() {
        super(root);
    }

    @Override
    public void onMatch(@Nonnull PlannerRuleCall call) {
        final LogicalSortExpression sortExpression = call.get(root);
        final PlanPartition innerPlanPartition = call.get(innerPlanPartitionMatcher);

        final GroupExpressionRef<? extends RecordQueryPlan> referenceOverPlans = GroupExpressionRef.from(innerPlanPartition.getPlans());

        final KeyExpression sortKeyExpression = sortExpression.getSort();
        if (sortKeyExpression == null) {
            call.yield(referenceOverPlans);
            return;
        }

        final Ordering ordering = innerPlanPartition.getAttributeValue(OrderingProperty.ORDERING);
        final Set<KeyExpression> equalityBoundKeys = ordering.getEqualityBoundKeys();
        int equalityBoundUnsorted = equalityBoundKeys.size();
        final List<KeyPart> orderingKeys = ordering.getOrderingKeyParts();
        final Iterator<KeyPart> orderingKeysIterator = orderingKeys.iterator();

        final List<KeyExpression> normalizedSortExpressions = sortKeyExpression.normalizeKeyForPositions();
        for (final KeyExpression normalizedSortExpression : normalizedSortExpressions) {
            if (equalityBoundKeys.contains(normalizedSortExpression)) {
                equalityBoundUnsorted--;
                continue;
            }
            if (!orderingKeysIterator.hasNext()) {
                return;
            }

            final KeyPart currentOrderingKeyPart = orderingKeysIterator.next();

            if (!normalizedSortExpression.equals(currentOrderingKeyPart.getNormalizedKeyExpression())) {
                return;
            }
        }

        final var resultExpressionsBuilder = ImmutableList.<RelationalExpression>builder();

        for (final var innerPlan : innerPlanPartition.getPlans()) {
            final boolean strictOrdered =
                    // If we have exhausted the ordering info's keys, too, then its constituents are strictly ordered.
                    !orderingKeysIterator.hasNext() ||
                    // Also a unique index if have gone through declared fields.
                    strictlyOrderedIfUnique(innerPlan, normalizedSortExpressions.size() + equalityBoundUnsorted);

            if (strictOrdered) {
                resultExpressionsBuilder.add(innerPlan.strictlySorted());
            } else {
                resultExpressionsBuilder.add(innerPlan);
            }
        }

        final var resultExpressions = resultExpressionsBuilder.build();
        call.yield(GroupExpressionRef.from(resultExpressions));
    }

    public static boolean strictlyOrderedIfUnique(@Nonnull RecordQueryPlan orderedPlan, final int nkeys) {
        if (orderedPlan instanceof RecordQueryCoveringIndexPlan) {
            orderedPlan = ((RecordQueryCoveringIndexPlan)orderedPlan).getIndexPlan();
        }
        if (orderedPlan instanceof RecordQueryIndexPlan) {
            RecordQueryIndexPlan indexPlan = (RecordQueryIndexPlan)orderedPlan;
            final var matchCandidateOptional = indexPlan.getMatchCandidateMaybe();
            if (matchCandidateOptional.isPresent()) {
                final var matchCandidate = matchCandidateOptional.get();
                final var index = matchCandidate.getIndex();
                return index.isUnique() && nkeys >= index.getColumnSize();
            }
        }
        return false;
    }
}
