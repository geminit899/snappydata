Select sum(ArrDelay) as x from airline;
Select  uniqueCarrier, sum(ArrDelay) as x from airline group by uniqueCarrier;
Select sum(ArrDelay) as x from sampleTable_WOE;
Select sum(ArrDelay) as x from airline  with error 0.02 behavior 'do_nothing';
Select sum(ArrDelay) as x , absolute_error(x),relative_error(x) from airline  with error 0.02 behavior 'do_nothing';
Select  uniqueCarrier, sum(ArrDelay) as x from sampleTable_WOE group by uniqueCarrier;
Select  uniqueCarrier, sum(ArrDelay) as x from airline group by uniqueCarrier with error 0.02 behavior 'do_nothing';
Select  uniqueCarrier, sum(ArrDelay) as x , absolute_error(x),relative_error(x)  from airline group by uniqueCarrier with error 0.02 behavior 'do_nothing';
