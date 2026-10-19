#!/usr/bin/env python3
"""Fail-closed local acceptance evidence from Gradle JUnit XML, no network access."""
import argparse
import datetime
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

CASES = {
 'authorization': ['missingReadGrantCannotInvokeModelOrPublishDocument','missingInferenceGrantDeniesBeforeExternalHttp','exactGrantsGenerateOnceAndRevocationPreventsReplay'],
 'revocation': ['crossJvmPublicationCompletesBeforeOperatorRevocationAcknowledgement','crossJvmRevocationFirstDeniesDocumentPublication','revocationDuringHttpDeniesPublicationAndLeavesAmbiguousReservation'],
 'recovery': ['separateJvmRecoversAfterAbruptCrashAtEveryPublicationBoundary','revokedStagingCannotBeRecoveredByIndependentJvm','tamperedStagingCannotBeRecoveredByIndependentJvm','twoJvmCompeteToRecoverUnfinishedPublication'],
 'idempotence': ['twoJvmConcurrentReplayUsesOneDurableArtifact','restartAfterArtifactAppendDoesNotDuplicateEvidence','ambiguousReservedInvocationCannotBeAutomaticallyRetried'],
 'integrity': ['alteredStagingIsRejectedWithoutReinvokingModel','alteredFinalMaterialIsRejectedWithoutReinvokingModel','alteredCompletedArtifactIsRejectedOnReplay'],
 'audit': ['eventBusAuditWriteFailureDeniesBeforeHttp','postHttpAuditFailureDeniesPublicationAndPreservesAmbiguousReservation','restartReconcilesEveryPublicationBoundaryWithoutSecondHttp'],
}


def inspect(root):
    reports = sorted(root.rglob('TEST-*.xml')) if root.is_dir() else []
    cases = {}
    invalid = []
    suites = 0
    for report in reports:
        try:
            tree = ET.parse(report)
            for suite in ([tree.getroot()] if tree.getroot().tag == 'testsuite' else tree.getroot().findall('testsuite')):
                suites += 1
                for case in suite.findall('testcase'):
                    key = (case.get('classname',''), case.get('name',''))
                    state = 'FAIL' if any(case.find(t) is not None for t in ('failure','error')) else ('SKIP' if case.find('skipped') is not None else 'PASS')
                    cases.setdefault(key, []).append({'state':state,'report':str(report)})
        except (ET.ParseError, OSError) as exc:
            invalid.append({'file':str(report),'error':str(exc)})
    matrix = {}
    for category, methods in CASES.items():
        entries = []
        for method in methods:
            matches = [v for (cls,name), records in cases.items() if cls.endswith('.GovernedDocumentationConsumerE2ETest') and name.startswith(method+'(') for v in records]
            # Ambiguous duplicates fail closed, including contradictory reruns.
            state = 'PASS' if len(matches)==1 and matches[0]['state']=='PASS' else ('MISSING' if not matches else 'FAIL_OR_DUPLICATE')
            entries.append({'test':method,'state':state,'evidence':matches})
        matrix[category] = entries
    # Ensure a complete suite exists; targeted-only output is not a release report.
    unique = len(cases)
    ready = bool(reports) and not invalid and all(e['state']=='PASS' for entries in matrix.values() for e in entries) and unique >= 100
    return {'schema':'aies.assurance.v1','generated_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':str(root),'xml_files':len(reports),'suites':suites,'unique_test_cases':unique,'invalid_xml':invalid,'matrix':matrix,'automated_evidence_complete':ready,'release_decision':'NO-GO: human approval and deployment verification required' if ready else 'NO-GO: incomplete or failing automated evidence'}


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--reports',type=pathlib.Path,default=pathlib.Path('.'))
    ap.add_argument('--output',type=pathlib.Path,required=True)
    args=ap.parse_args()
    result=inspect(args.reports)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(f"{result['release_decision']} | {result['unique_test_cases']} tests | {args.output}")
    return 0 if result['automated_evidence_complete'] else 2

if __name__=='__main__': sys.exit(main())
