package gh.edu.clet.sfl.facilities.catering.domain;

/**
 * Where reconciliation stands against finance. S172 is not the ledger and there is no finance integration yet,
 * so the state is honest: PENDING_FINANCE until references are recorded by hand, and RECORDED means "the
 * references were entered", never "finance has matched them".
 */
public enum FinanceState { NOT_STARTED, PENDING_FINANCE, RECORDED }
