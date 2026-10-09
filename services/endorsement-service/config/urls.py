from django.urls import include, path

from apps.jobs import internal

urlpatterns = [
    path("api/v1/internal/endorsements/<uuid:job_id>/members/", internal.JobMembersView.as_view()),
    path("api/v1/internal/endorsements/<uuid:job_id>/tpa-member-ids/", internal.TpaMemberIdsView.as_view()),
    path("api/v1/internal/policies/<int:policy_id>/members/", internal.PolicyMembersView.as_view()),
    path("api/v1/internal/policies/<int:policy_id>/tpa-member-ids/", internal.PolicyTpaMemberIdsView.as_view()),
    path("api/v1/internal/endorsements/<uuid:job_id>/lifecycle/", internal.LifecycleView.as_view()),
    path("api/v1/internal/members/pending-summary/", internal.PendingSummaryView.as_view()),
    path("api/v1/broker/endorsements/", include("apps.jobs.urls")),
    path("api/v1/employer/endorsements/", include("apps.jobs.urls")),
]
