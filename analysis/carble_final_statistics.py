#!/usr/bin/env python3
from __future__ import annotations
import argparse
from pathlib import Path
from typing import Iterable
import numpy as np
import pandas as pd
from scipy import stats

PROTOCOL_ORDER=["B0","MM","TWO_RH","CARBLE"]
PERFORMANCE_METRICS=["pdr","conditionalMeanLatency","conditionalMedianLatency","physicalAttempts","attemptsPerGenerated","attemptsPerDelivered","retransmissions"]
RESOURCE_METRICS=["physicalAttempts","retransmissions","attemptsPerGenerated","attemptsPerDelivered","retransmissionsPerDelivered","totalRelayAttempts","totalRelayForwards","maxRelayAttemptShare","maxRelayForwardShare","maxMeanRelayAttemptRatio","jainRelayAttemptFairness","jainRelayForwardFairness"]

def arr(v):
    x=np.asarray(list(v),dtype=float); return x[np.isfinite(x)]
def descriptive(v):
    x=arr(v); n=len(x)
    if n==0:return dict(n=0,mean=np.nan,median=np.nan,sd=np.nan,min=np.nan,max=np.nan,ci95Low=np.nan,ci95High=np.nan)
    mean=float(x.mean()); med=float(np.median(x)); sd=float(np.std(x,ddof=1)) if n>1 else 0.0
    if n==1 or np.allclose(x,x[0]): lo=hi=mean
    else:
        b=stats.bootstrap((x,),np.mean,vectorized=False,confidence_level=.95,method='BCa',n_resamples=10000,random_state=20260905)
        lo=float(b.confidence_interval.low); hi=float(b.confidence_interval.high)
    return dict(n=n,mean=mean,median=med,sd=sd,min=float(x.min()),max=float(x.max()),ci95Low=lo,ci95High=hi)
def wilcoxon_p(d):
    d=arr(d)
    if len(d)==0:return np.nan
    if np.allclose(d,0):return 1.0
    try:return float(stats.wilcoxon(d,zero_method='wilcox',alternative='two-sided',method='auto').pvalue)
    except ValueError:return 1.0
def rrb(d):
    d=arr(d); d=d[d!=0]
    if len(d)==0:return 0.0
    r=stats.rankdata(np.abs(d),method='average'); wp=float(r[d>0].sum()); wm=float(r[d<0].sum())
    return 0.0 if wp+wm==0 else (wp-wm)/(wp+wm)
def holm(p):
    p=np.asarray(list(p),dtype=float); out=np.full(len(p),np.nan); idx=np.where(np.isfinite(p))[0]
    if not len(idx):return out.tolist()
    vals=p[idx]; order=np.argsort(vals); m=len(vals); adj=np.empty(m); run=0
    for rank,pos in enumerate(order):
        run=max(run,(m-rank)*vals[pos]); adj[pos]=min(1.0,run)
    out[idx]=adj; return out.tolist()
def read(p): return pd.read_csv(Path(p))
def validate_unique(df,keys,name):
    m=df.duplicated(keys,keep=False)
    if m.any(): raise ValueError(f"{name}: duplicate rows on {keys}\n{df.loc[m,keys].to_string(index=False)}")

def validate(pref,full,audit,events,resource,relay):
    req={
      'prefailure':(pref,{"condition","protocol","seed","pdr","conditionalMeanLatency","conditionalMedianLatency","attemptsPerGenerated","attemptsPerDelivered","twoRhHighDecisions","twoRhLowDecisions","carbleHighDecisions","carbleM1Decisions","carbleM2Decisions","carbleM3Decisions","carbleLowDecisions"}),
      'full':(full,{"protocol","seed","pdr","conditionalMeanLatency","conditionalMedianLatency","attemptsPerGenerated","attemptsPerDelivered","retransmissions","twoRhHighDecisions","twoRhLowDecisions","carbleHighDecisions","carbleM1Decisions","carbleM2Decisions","carbleM3Decisions","carbleLowDecisions"}),
      'audit':(audit,{"protocol","seed","hasAllStages","strictFirstEntryOrder","firstM1Time","firstM2Time","firstM3Time","firstLowTime","m1ToM2","m2ToM3","m3ToLow","m1ToLowLeadTime"}),
      'events':(events,{"protocol","seed","eventTime","regime","mediumStage","reason","action"}),
      'resource':(resource,{"protocol","seed","attemptsPerGenerated","attemptsPerDelivered","retransmissionsPerDelivered","maxRelayAttemptShare","jainRelayAttemptFairness"}),
      'relay':(relay,{"protocol","seed","nodeId","isRelay","physicalAttempts","retransmissions","relayAttemptShare","relayForwardShare"}),}
    for n,(df,cols) in req.items():
        miss=cols-set(df.columns)
        if miss: raise ValueError(f"{n} missing columns: {sorted(miss)}")
    validate_unique(pref,["condition","protocol","seed"],"prefailure")
    validate_unique(full,["protocol","seed"],"full")
    validate_unique(audit,["protocol","seed"],"audit")
    validate_unique(resource,["protocol","seed"],"resource")
    validate_unique(relay,["protocol","seed","nodeId"],"relay")

def combined_runs(pref,full):
    p=pref.copy(); p['scenario']=p['condition'].astype(str)
    f=full.copy(); f['scenario']='FULL_DEGRADATION'
    cols=sorted(set(p.columns)|set(f.columns)); return pd.concat([p.reindex(columns=cols),f.reindex(columns=cols)],ignore_index=True)
def performance_summary(df):
    rows=[]
    for (s,p),g in df.groupby(['scenario','protocol'],sort=False):
        for m in PERFORMANCE_METRICS:
            if m in g: rows.append(dict(scenario=s,protocol=p,metric=m,**descriptive(g[m])))
    return pd.DataFrame(rows)
def paired(df):
    rows=[]
    for s,sg in df.groupby('scenario',sort=False):
        c=sg[sg.protocol=='CARBLE']
        for comp,role in [('TWO_RH','primary'),('MM','secondary'),('B0','secondary')]:
            o=sg[sg.protocol==comp]
            if c.empty or o.empty: continue
            z=c.merge(o,on='seed',suffixes=('_carble','_other'),validate='one_to_one')
            for m in PERFORMANCE_METRICS:
                cc=f'{m}_carble'; oo=f'{m}_other'
                if cc not in z or oo not in z: continue
                v=z[[cc,oo]].dropna(); d=v[cc].to_numpy(float)-v[oo].to_numpy(float)
                ds=descriptive(d)
                rows.append(dict(scenario=s,comparison=f'CARBLE_vs_{comp}',comparisonRole=role,metric=m,nPairs=ds['n'],carbleMean=float(v[cc].mean()),comparatorMean=float(v[oo].mean()),meanDifference=ds['mean'],medianDifference=ds['median'],sdDifference=ds['sd'],ci95Low=ds['ci95Low'],ci95High=ds['ci95High'],wilcoxonRawP=wilcoxon_p(d),rankBiserial=rrb(d)))
    out=pd.DataFrame(rows)
    if not out.empty:
        out['holmAdjustedP_all']=holm(out.wilcoxonRawP)
        out['holmAdjustedP_pdrFamily']=np.nan
        m=out.metric.eq('pdr'); out.loc[m,'holmAdjustedP_pdrFamily']=holm(out.loc[m,'wilcoxonRawP'])
    return out
def mechanism(df):
    rows=[]
    for s,sg in df.groupby('scenario',sort=False):
        t=sg[sg.protocol=='TWO_RH']
        if not t.empty:
            den=t.twoRhHighDecisions+t.twoRhLowDecisions
            for state,col in [('HIGH','twoRhHighDecisions'),('LOW','twoRhLowDecisions')]:
                sh=np.where(den>0,t[col]/den,np.nan); ds=descriptive(sh)
                rows.append(dict(scenario=s,protocol='TWO_RH',state=state,meanDecisionCount=float(t[col].mean()),**{f'share_{k}':v for k,v in ds.items()}))
        c=sg[sg.protocol=='CARBLE']
        if not c.empty:
            states=[('HIGH','carbleHighDecisions'),('M1','carbleM1Decisions'),('M2','carbleM2Decisions'),('M3','carbleM3Decisions'),('LOW','carbleLowDecisions')]
            den=sum(c[col] for _,col in states)
            for state,col in states:
                sh=np.where(den>0,c[col]/den,np.nan); ds=descriptive(sh)
                rows.append(dict(scenario=s,protocol='CARBLE',state=state,meanDecisionCount=float(c[col].mean()),**{f'share_{k}':v for k,v in ds.items()}))
    return pd.DataFrame(rows)
def transition(audit):
    c=audit[audit.protocol=='CARBLE']
    metrics=['firstHighTime','firstM1Time','firstM2Time','firstM3Time','firstLowTime','m1ToM2','m2ToM3','m3ToLow','m1ToLowLeadTime','minCurrentHopConfidence','minRouteConfidence']
    t=pd.DataFrame([dict(metric=m,**descriptive(c[m])) for m in metrics if m in c])
    n=len(c); a=int(c.hasAllStages.fillna(False).astype(bool).sum()); q=int(c.strictFirstEntryOrder.fillna(False).astype(bool).sum())
    o=pd.DataFrame([dict(nSeeds=n,allStagesCount=a,allStagesRate=a/n if n else np.nan,strictOrderCount=q,strictOrderRate=q/n if n else np.nan)])
    return t,o
def event_summary(events):
    c=events[events.protocol=='CARBLE']; rows=[]
    for seed,g in c.groupby('seed'):
        total=len(g); row={'seed':seed,'totalEvents':total}
        for col,prefix,names in [
            ('reason','reason',['HEALTHY_ROUTE','LOCAL_MEDIUM','DOWNSTREAM_WARNING','NO_ROUTE','LOCAL_LOW']),
            ('action','action',['FORWARD','FORWARD_WITH_DELAYED_BACKUP','FORWARD_WITH_FAILOVER','CARRY','PROBE','DROP']),
            ('regime','regime',['HIGH','MEDIUM','LOW']),
            ('mediumStage','stage',['M1','M2','M3'])]:
            vc=g[col].value_counts(dropna=False)
            for name in names:
                count=int(vc.get(name,0)); row[f'{prefix}_{name}']=count; row[f'{prefix}Share_{name}']=count/total if total else np.nan
        rows.append(row)
    per=pd.DataFrame(rows); summ=[]
    for col in [c for c in per.columns if 'Share_' in c or c.startswith('regimeShare_') or c.startswith('stageShare_')]: summ.append(dict(metric=col,**descriptive(per[col])))
    return per,pd.DataFrame(summ)
def resource_summary(r):
    rows=[]
    for p,g in r.groupby('protocol',sort=False):
        for m in RESOURCE_METRICS:
            if m in g: rows.append(dict(scenario='FULL_DEGRADATION',protocol=p,metric=m,**descriptive(g[m])))
    return pd.DataFrame(rows)
def relay_summary(relay):
    rel=relay[relay.isRelay.astype(bool)]; rows=[]
    for (p,s),g in rel.groupby(['protocol','seed'],sort=False):
        rows.append(dict(protocol=p,seed=s,relayCount=len(g),meanRelayPhysicalAttempts=float(g.physicalAttempts.mean()),maxRelayPhysicalAttempts=float(g.physicalAttempts.max()),meanRelaySuccessfulForwards=float(g.successfulForwards.mean()),maxRelaySuccessfulForwards=float(g.successfulForwards.max()),meanRelayRetransmissions=float(g.retransmissions.mean()),maxRelayRetransmissions=float(g.retransmissions.max()),maxRelayAttemptShare=float(g.relayAttemptShare.max(skipna=True)),maxRelayForwardShare=float(g.relayForwardShare.max(skipna=True))))
    per=pd.DataFrame(rows); sm=[]
    for p,g in per.groupby('protocol',sort=False):
        for m in ['relayCount','meanRelayPhysicalAttempts','maxRelayPhysicalAttempts','meanRelaySuccessfulForwards','maxRelaySuccessfulForwards','meanRelayRetransmissions','maxRelayRetransmissions','maxRelayAttemptShare','maxRelayForwardShare']:
            sm.append(dict(scenario='FULL_DEGRADATION',protocol=p,metric=m,**descriptive(g[m])))
    return per,pd.DataFrame(sm)
def main():
    ap=argparse.ArgumentParser();
    for a in ['prefailure','full','audit','events','resource','relay','outdir']: ap.add_argument(f'--{a}',required=True)
    x=ap.parse_args(); pref,full,audit,events,resource,relay=map(read,[x.prefailure,x.full,x.audit,x.events,x.resource,x.relay]); validate(pref,full,audit,events,resource,relay); comb=combined_runs(pref,full); out=Path(x.outdir); out.mkdir(parents=True,exist_ok=True)
    outputs={
      'protocol_performance_summary.csv':performance_summary(comb),
      'paired_protocol_comparisons.csv':paired(comb),
      'mechanism_summary.csv':mechanism(comb),
      'resource_cost_summary.csv':resource_summary(resource),
    }
    t,o=transition(audit); outputs['transition_timing_summary.csv']=t; outputs['transition_order_summary.csv']=o
    ep,es=event_summary(events); outputs['event_mechanism_per_seed.csv']=ep; outputs['event_mechanism_summary.csv']=es
    rp,rs=relay_summary(relay); outputs['relay_burden_per_seed.csv']=rp; outputs['relay_burden_summary.csv']=rs
    for fn,df in outputs.items(): df.to_csv(out/fn,index=False); print('Wrote:',out/fn)
if __name__=='__main__': main()
