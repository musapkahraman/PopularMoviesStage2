package com.udacity.and.popularmovies;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import com.udacity.and.popularmovies.adapters.PostersAdapter;
import com.udacity.and.popularmovies.data.FavoritesContract;
import com.udacity.and.popularmovies.data.MovieDetails;
import com.udacity.and.popularmovies.data.UserPrefs;
import com.udacity.and.popularmovies.utilities.JsonUtils;
import com.udacity.and.popularmovies.utilities.NetworkUtils;

import java.io.IOException;
import java.net.URL;

import butterknife.BindView;
import butterknife.ButterKnife;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

public class MainActivity extends AppCompatActivity
        implements IListItemClickListener, LoaderManager.LoaderCallbacks<Object>,
        SharedPreferences.OnSharedPreferenceChangeListener, AdapterView.OnItemSelectedListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String POSTERS_STATE_KEY = "poster_state";
    private static final int MOVIE_DATA_LOADER = 20;
    private static final int EXTENTION_LOADER = 21;
    private static final int FAVORITES_LOADER = 22;
    private static final String TAG_MOVIE_ID = "movie_id";
    private static final String TAG_CURRENT_PAGE = "current_page";
    private static final int DIRECTION_UP = -1;
    private static final int DIRECTION_DOWN = 1;
    private static final int FIRST_PAGE = 1;
    @BindView(R.id.rv_movie_posters)
    RecyclerView mMoviePosters;
    private PostersAdapter mAdapter;
    private int mMaxPosterWidth;
    private GridLayoutManager mLayoutManager;
    private Parcelable mMoviePostersState;
    private int mCurrentPage;
    private int mExtendingPage;
    private int mLastPage;
    private boolean isAddingToTail;
    private int mFirstVisibleItemPos;
    private int mLastVisibleItemPos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        NetworkUtils.PARAM_API_KEY = getString(R.string.the_movie_database_api_key);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        UserPrefs.setImageQuality(
                Integer.parseInt(sharedPreferences.getString(
                        getString(R.string.pref_quality_key),
                        getString(R.string.pref_quality_value_2))));
        mLayoutManager = new GridLayoutManager(this, optimizePosterWidth());
        mMoviePosters.setLayoutManager(mLayoutManager);
        mMoviePosters.setHasFixedSize(true);
        mAdapter = new PostersAdapter(this, mMaxPosterWidth);
        mMoviePosters.setAdapter(mAdapter);
        mMoviePosters.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (UserPrefs.getSortOrder() == NetworkUtils.SortOrder.FAVORITES
                        || !NetworkUtils.isOnline(MainActivity.this)) {
                    return;
                }
                if (!recyclerView.canScrollVertically(DIRECTION_DOWN)) {
                    if (mCurrentPage < mLastPage) {
                        mExtendingPage = mCurrentPage + 1;
                        isAddingToTail = true;
                        mFirstVisibleItemPos = mLayoutManager.findFirstVisibleItemPosition();
                        getSupportLoaderManager()
                                .restartLoader(EXTENTION_LOADER, null, MainActivity.this);

                    }
                } else if (!recyclerView.canScrollVertically(DIRECTION_UP)) {
                    if (mCurrentPage > FIRST_PAGE) {
                        mExtendingPage = mCurrentPage - 1;
                        isAddingToTail = false;
                        mLastVisibleItemPos = mLayoutManager.findLastVisibleItemPosition();
                        getSupportLoaderManager()
                                .restartLoader(EXTENTION_LOADER, null, MainActivity.this);

                    }
                }
            }
        });
        loadMoviesData();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mMoviePostersState = mLayoutManager.onSaveInstanceState();
        outState.putParcelable(POSTERS_STATE_KEY, mMoviePostersState);
        outState.putInt(TAG_CURRENT_PAGE, mCurrentPage);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            mMoviePostersState = savedInstanceState.getParcelable(POSTERS_STATE_KEY);
            mCurrentPage = savedInstanceState.getInt(TAG_CURRENT_PAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (UserPrefs.getSortOrder() == NetworkUtils.SortOrder.FAVORITES) {
            getSupportLoaderManager().restartLoader(FAVORITES_LOADER, null, this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    private int optimizePosterWidth() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int posterGridColCount = UserPrefs.getGridColumnCount(metrics);
        if (getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE) {
            posterGridColCount *= (metrics.widthPixels / metrics.heightPixels);
        }
        mMaxPosterWidth = metrics.widthPixels / posterGridColCount;
        return posterGridColCount;
    }

    private void loadMoviesData() {
        if (UserPrefs.getSortOrder() == NetworkUtils.SortOrder.FAVORITES) {
            getSupportLoaderManager().restartLoader(FAVORITES_LOADER, null, this);
        } else {
            if (!NetworkUtils.isOnline(this)) {
                Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_SHORT).show();
                return;
            }
            getSupportLoaderManager().restartLoader(MOVIE_DATA_LOADER, null, this);
        }
    }

    @Override
    public void onListItemClick(int clickedItemIndex, View itemView) {
        int movieId = (int) itemView.getTag();
        Intent intent = new Intent(this, DetailsActivity.class);
        intent.putExtra(TAG_MOVIE_ID, movieId);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        Spinner spinner = (Spinner) menu.findItem(R.id.spinner).getActionView();
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.sort_style_array, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(UserPrefs.setSortOrder(this));
        return true;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (position) {
            case 0:
                mMoviePostersState = null;
                UserPrefs.setSortOrder(NetworkUtils.SortOrder.MOST_POPULAR);
                loadMoviesData();
                break;
            case 1:
                mMoviePostersState = null;
                UserPrefs.setSortOrder(NetworkUtils.SortOrder.TOP_RATED);
                loadMoviesData();
                break;
            case 2:
                mMoviePostersState = null;
                UserPrefs.setSortOrder(NetworkUtils.SortOrder.FAVORITES);
                loadMoviesData();
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.menu_settings:
                Intent startSettingsActivity = new Intent(this, SettingsActivity.class);
                startActivity(startSettingsActivity);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(getString(R.string.pref_quality_key))) {
            String pref = sharedPreferences.getString(getString(R.string.pref_quality_key),
                    getString(R.string.pref_quality_value_2));
            UserPrefs.setImageQuality(Integer.parseInt(pref));
            mLayoutManager.setSpanCount(optimizePosterWidth());
            mAdapter.setMaxPosterWidth(mMaxPosterWidth);
        }
    }

    @NonNull
    @Override
    public Loader<Object> onCreateLoader(final int id, Bundle args) {
        return new AsyncTaskLoader<Object>(this) {

            @Override
            protected void onStartLoading() {
                forceLoad();
            }

            @Override
            public Object loadInBackground() {
                switch (id) {
                    case FAVORITES_LOADER:
                        try {
                            return getContentResolver().query(FavoritesContract.FavoritesEntry.CONTENT_URI,
                                    null,
                                    null,
                                    null,
                                    FavoritesContract.FavoritesEntry.COLUMN_TIMESTAMP);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to asynchronously load data.");
                            e.printStackTrace();
                            return null;
                        }
                    case MOVIE_DATA_LOADER:
                        URL movieRequestUrl = NetworkUtils.generateURL(UserPrefs.getSortOrder(), mCurrentPage);
                        try {
                            return NetworkUtils.getResponseFromHttpUrl(movieRequestUrl);
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to asynchronously load data.");
                            e.printStackTrace();
                            return null;
                        }
                    case EXTENTION_LOADER:
                        URL movieExtendRequestUrl = NetworkUtils.generateURL(UserPrefs.getSortOrder(), mExtendingPage);
                        try {
                            return NetworkUtils.getResponseFromHttpUrl(movieExtendRequestUrl);
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to asynchronously load data.");
                            e.printStackTrace();
                            return null;
                        }
                    default:
                        return null;
                }
            }
        };
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Object> loader, Object data) {
        if (data != null) {
            if (data instanceof String) {
                if (loader.getId() == EXTENTION_LOADER) {
                    PopulateMoviePostersListExtension((String) data);
                } else {
                    JsonUtils.extractMovieDataFromJson((String) data);
                }
                mLastPage = MovieDetails.getPageCount();
                mCurrentPage = MovieDetails.getCurrentPage();
                Log.v("onLoadFinished", mCurrentPage + "/" + mLastPage);
                Toast.makeText(this, mCurrentPage + "/" + mLastPage, Toast.LENGTH_SHORT).show();
                mAdapter.setCursor(null);
            } else if (data instanceof Cursor) {
                mAdapter.setCursor((Cursor) data);
            }

            if (mMoviePostersState != null) {
                mLayoutManager.onRestoreInstanceState(mMoviePostersState);
            }
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Object> loader) {
    }

    private void PopulateMoviePostersListExtension(String data) {
        int[] extendedIds = new int[40];
        String[] extendedPosters = new String[40];
        if (isAddingToTail) {
            if (MovieDetails.getMoviesCountInPage() <= 20) {
                for (int i = 0; i < 20; i++) {
                    extendedIds[i] = MovieDetails.getId(i);
                    extendedPosters[i] = MovieDetails.getImagePath(i);
                }
                JsonUtils.extractMovieDataFromJson(data);
                for (int i = 20; i < 40; i++) {
                    extendedIds[i] = MovieDetails.getId(i - 20);
                    extendedPosters[i] = MovieDetails.getImagePath(i - 20);
                }
            } else {
                for (int i = 0; i < 20; i++) {
                    extendedIds[i] = MovieDetails.getId(i + 20);
                    extendedPosters[i] = MovieDetails.getImagePath(i + 20);
                }
                JsonUtils.extractMovieDataFromJson(data);
                for (int i = 20; i < 40; i++) {
                    extendedIds[i] = MovieDetails.getId(i - 20);
                    extendedPosters[i] = MovieDetails.getImagePath(i - 20);
                }
                mMoviePosters.scrollToPosition(mFirstVisibleItemPos - 20);
            }
        } else {
            for (int i = 20; i < 40; i++) {
                extendedIds[i] = MovieDetails.getId(i - 20);
                extendedPosters[i] = MovieDetails.getImagePath(i - 20);
            }
            JsonUtils.extractMovieDataFromJson(data);
            for (int i = 0; i < 20; i++) {
                extendedIds[i] = MovieDetails.getId(i);
                extendedPosters[i] = MovieDetails.getImagePath(i);
            }
            mMoviePosters.scrollToPosition(mLastVisibleItemPos + 20);
        }
        MovieDetails.setIds(extendedIds);
        MovieDetails.setPosterPaths(extendedPosters);
        mAdapter.notifyDataSetChanged();
    }
}
