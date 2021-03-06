package org.stepic.droid.base;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.FragmentActivity;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Toast;

import com.squareup.otto.Subscribe;

import org.stepic.droid.R;
import org.stepic.droid.analytic.Analytic;
import org.stepic.droid.concurrency.tasks.FromDbCoursesTask;
import org.stepic.droid.concurrency.tasks.ToDbCoursesTask;
import org.stepic.droid.events.courses.FailCoursesDownloadEvent;
import org.stepic.droid.events.courses.FailDropCourseEvent;
import org.stepic.droid.events.courses.FinishingGetCoursesFromDbEvent;
import org.stepic.droid.events.courses.FinishingSaveCoursesToDbEvent;
import org.stepic.droid.events.courses.GettingCoursesFromDbSuccessEvent;
import org.stepic.droid.events.courses.PreLoadCoursesEvent;
import org.stepic.droid.events.courses.StartingGetCoursesFromDbEvent;
import org.stepic.droid.events.courses.StartingSaveCoursesToDbEvent;
import org.stepic.droid.events.courses.SuccessCoursesDownloadEvent;
import org.stepic.droid.events.courses.SuccessDropCourseEvent;
import org.stepic.droid.events.joining_course.SuccessJoinEvent;
import org.stepic.droid.model.Course;
import org.stepic.droid.store.operations.DatabaseFacade;
import org.stepic.droid.util.AppConstants;
import org.stepic.droid.util.KotlinUtil;
import org.stepic.droid.util.ProgressHelper;
import org.stepic.droid.ui.fragments.CourseListFragmentBase;
import org.stepic.droid.web.CoursesStepicResponse;

import java.util.List;

import retrofit.Call;
import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;

public abstract class CoursesDatabaseFragmentBase extends CourseListFragmentBase {
    protected ToDbCoursesTask mDbSaveCoursesTask;
    protected FromDbCoursesTask mDbFromCoursesTask;
    private static final int FILTER_REQUEST_CODE = 776;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
//        inflater.inflate(R.menu.my_courses_menu, menu); //hide in 1.15
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_filter_menu:
                mShell.getScreenProvider().showFilterScreen(this, FILTER_REQUEST_CODE);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        bus.register(this);
        mSwipeRefreshLayout.post(new Runnable() {
            @Override
            public void run() {
                getAndShowDataFromCache();
            }
        });
    }

    protected void showCourses(List<Course> cachedCourses) {
        if (cachedCourses == null) return;
        if (!cachedCourses.isEmpty()) {
            showEmptyScreen(false);
            mReportConnectionProblem.setVisibility(View.GONE);
        }

        mCourses.clear();
        cachedCourses = KotlinUtil.INSTANCE.filterIfNotUnique(cachedCourses);
        if (getCourseType() == DatabaseFacade.Table.enrolled) {
            for (Course course : cachedCourses) {
                if (course.getEnrollment() != 0)
                    mCourses.add(course);
            }
        } else {
            mCourses.addAll(cachedCourses);
        }

        mCoursesAdapter.notifyDataSetChanged();
    }

    private void saveDataToCache(List<Course> courses) {
        mDbSaveCoursesTask = new ToDbCoursesTask(courses, getCourseType(), mCurrentPage);
        mDbSaveCoursesTask.executeOnExecutor(mThreadPoolExecutor);
    }


    public void getAndShowDataFromCache() {
        mDbFromCoursesTask = new FromDbCoursesTask(getCourseType()) {
            @Override
            protected void onSuccess(List<Course> courses) {
                super.onSuccess(courses);
                bus.post(new GettingCoursesFromDbSuccessEvent(getCourseType(), courses));
            }
        };
        mDbFromCoursesTask.executeOnExecutor(mThreadPoolExecutor);
    }

    @Subscribe
    public void onPreLoad(PreLoadCoursesEvent e) {
        isLoading = true;
        if (mCourses.isEmpty()) {
            ProgressHelper.activate(mSwipeRefreshLayout);
        } else if (mSwipeRefreshLayout != null && !mSwipeRefreshLayout.isRefreshing()) {
            mFooterDownloadingView.setVisibility(View.VISIBLE);
        }
    }

    @Subscribe
    public void onSuccessDataLoad(SuccessCoursesDownloadEvent e) {
        Response<CoursesStepicResponse> response = e.getResponse();
        if (response.body() != null &&
                response.body().getCourses() != null &&
                response.body().getCourses().size() != 0) {
            CoursesStepicResponse coursesStepicResponse = response.body();
            ProgressHelper.dismiss(mSwipeRefreshLayout);
            saveDataToCache(coursesStepicResponse.getCourses());

            mHasNextPage = coursesStepicResponse.getMeta().getHas_next();
            if (mHasNextPage) {
                mCurrentPage = coursesStepicResponse.getMeta().getPage() + 1;
            }
        } else {
            mHasNextPage = false;
            mReportConnectionProblem.setVisibility(View.GONE);
            showEmptyScreen(true);

            mFooterDownloadingView.setVisibility(View.GONE);
            ProgressHelper.dismiss(mSwipeRefreshLayout);
        }
        isLoading = false;
    }

    @Subscribe
    public void onFailureDataLoad(FailCoursesDownloadEvent e) {
        super.onFailureDataLoad(e);
    }


    @Subscribe
    public void onStartingSaveToDb(StartingSaveCoursesToDbEvent e) {
//        ProgressHelper.activate(mSwipeRefreshLayout);
    }

    @Subscribe
    public void onFinishingSaveToDb(FinishingSaveCoursesToDbEvent e) {
        ProgressHelper.dismiss(mSwipeRefreshLayout);
        getAndShowDataFromCache();
    }

    @Subscribe
    public void onStartingGetFromDb(StartingGetCoursesFromDbEvent e) {
//        ProgressHelper.activate(mSwipeRefreshLayout);
    }

    @Subscribe
    public void onFinishingGetFromDb(FinishingGetCoursesFromDbEvent e) {
        ProgressHelper.dismiss(mSwipeRefreshLayout);
        if (mFooterDownloadingView != null) mFooterDownloadingView.setVisibility(View.GONE);

        if (e.getResult() != null && e.getResult().size() == 0)
            downloadData();
    }

    @Subscribe
    public void onGettingFromDbSuccess(GettingCoursesFromDbSuccessEvent e) {
        showCourses(e.getCourses());
    }

    @Override
    @Subscribe
    public void onSuccessJoin(SuccessJoinEvent e) {
        //We do not upgrade database, because when
        //Only for find courses event.
        super.onSuccessJoin(e);
    }

    @Override
    public void onStart() {
        super.onStart();
        mSwipeRefreshLayout.setRefreshing(false);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }


    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        bus.unregister(this);
        super.onDestroyView();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;

        MenuInflater inflater = getActivity().getMenuInflater();
        if (mCourses.get(info.position).getEnrollment() != 0) {
            inflater.inflate(R.menu.course_context_menu, menu);
        } else {
            inflater.inflate(R.menu.course_context_not_enrolled_menu, menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        analytic.reportEvent(Analytic.Interaction.LONG_TAP_COURSE);
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        int position = info.position;
        switch (item.getItemId()) {
            case R.id.menu_item_info:
                showInfo(info.position);
                return true;
            case R.id.menu_item_unroll:
                dropCourse(info.position);
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void dropCourse(int position) {
        if (position >= mCourses.size() || position < 0) {
            Toast.makeText(getContext(), R.string.try_in_web_drop, Toast.LENGTH_LONG).show();
            return;
        }
        final Course course = mCourses.get(position);
        if (course.getEnrollment() == 0) {
            Toast.makeText(getContext(), R.string.you_not_enrolled, Toast.LENGTH_LONG).show();
            return;
        }
        Call<Void> drop = mShell.getApi().dropCourse(course.getCourseId());
        if (drop != null) {
            drop.enqueue(new Callback<Void>() {
                Course localRef = course;

                @Override
                public void onResponse(Response<Void> response, Retrofit retrofit) {

                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            mDatabaseFacade.deleteCourse(localRef, DatabaseFacade.Table.enrolled);

                            if (mDatabaseFacade.getCourseById(course.getCourseId(), DatabaseFacade.Table.featured) != null) {
                                localRef.setEnrollment(0);
                                mDatabaseFacade.addCourse(localRef, DatabaseFacade.Table.featured);
                            }

                        }
                    });

                    bus.post(new SuccessDropCourseEvent(getCourseType(), localRef));
                }

                @Override
                public void onFailure(Throwable t) {
                    bus.post(new FailDropCourseEvent(getCourseType(), localRef));
                }
            });
        } else {
            Toast.makeText(MainApplication.getAppContext(), R.string.cant_drop, Toast.LENGTH_SHORT).show();
        }
    }

    @Subscribe
    public void onSuccessDrop(final SuccessDropCourseEvent e) {
        long courseId = -1L;
        if (e.getCourse() != null) {
            courseId = e.getCourse().getCourseId();
        }
        analytic.reportEvent(Analytic.Web.DROP_COURSE_SUCCESSFUL, courseId + "");
        Toast.makeText(getContext(), getContext().getString(R.string.you_dropped) + " " + e.getCourse().getTitle(), Toast.LENGTH_LONG).show();
        if (e.getType() == DatabaseFacade.Table.enrolled) {
            mCourses.remove(e.getCourse());
            mCoursesAdapter.notifyDataSetChanged();
        }

        if (mCourses.size() == 0) {
            showEmptyScreen(true);
        }
    }

    @Subscribe
    public void onFailDrop(FailDropCourseEvent e) {
        long courseId = -1L;
        if (e.getCourse() != null) {
            courseId = e.getCourse().getCourseId();
        }
        analytic.reportEvent(Analytic.Web.DROP_COURSE_FAIL, courseId + "");
        Toast.makeText(getContext(), R.string.internet_problem, Toast.LENGTH_LONG).show();
    }

    private void showInfo(int position) {
        analytic.reportEvent(Analytic.Interaction.SHOW_DETAILED_INFO_CLICK);
        Course course = mCourses.get(position);
        mShell.getScreenProvider().showCourseDescription(this, course);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == FragmentActivity.RESULT_OK) {
            if (requestCode == AppConstants.REQUEST_CODE_DETAIL) {
                Course course = data.getParcelableExtra(AppConstants.COURSE_ID_KEY);
                int enrollment = data.getIntExtra(AppConstants.ENROLLMENT_KEY, 0);
                if (course != null && enrollment != 0) {
                    updateEnrollment(course, enrollment);
                }

            }
        }
    }

    @Override
    public void showEmptyScreen(boolean isShowed) {
        if (isShowed) {
            mEmptyCoursesView.setVisibility(View.VISIBLE);
            mSwipeRefreshLayout.setVisibility(View.GONE);
        } else {
            mEmptyCoursesView.setVisibility(View.GONE);
            mSwipeRefreshLayout.setVisibility(View.VISIBLE);

        }
    }
}
