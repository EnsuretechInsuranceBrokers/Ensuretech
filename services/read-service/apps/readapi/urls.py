from django.urls import path

from . import reports, stats, views

urlpatterns = [
    path("jobs/", views.JobListView.as_view()),
    path("jobs/<str:job_id>/", views.JobDetailView.as_view()),
    path("policies/", views.PolicyListView.as_view()),
    path("policies/<int:policy_id>/", views.PolicyDetailView.as_view()),
    path("cd/", views.CdListView.as_view()),
    path("dashboard/", views.DashboardView.as_view()),
    path("dashboard/endorsement-activity/", stats.EndorsementActivityView.as_view()),
    path("dashboard/red-flags/", stats.RedFlagsView.as_view()),
    path("reports/<str:report>/", reports.ReportView.as_view()),
]
