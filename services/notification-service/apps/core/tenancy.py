from rest_framework.exceptions import NotFound, PermissionDenied


def scope_filter(principal) -> dict:
    """ORM filter kwargs that confine a query to the caller's tenant. Used by every job lookup."""
    if principal.broker_id is None:
        raise PermissionDenied("Caller is not tenant-scoped")
    f = {"broker_id": principal.broker_id}
    if principal.is_employer:
        if principal.employer_id is None:
            raise PermissionDenied("Employer token without employer_id")
        f["employer_id"] = principal.employer_id
    return f


def get_scoped_or_404(queryset, principal, **lookup):
    """Cross-tenant ids are indistinguishable from missing ones (404, never 403)."""
    obj = queryset.filter(**scope_filter(principal), **lookup).first()
    if obj is None:
        raise NotFound()
    return obj
